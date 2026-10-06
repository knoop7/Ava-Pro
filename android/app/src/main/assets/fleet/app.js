(() => {
  const { t, setLang, getLang } = window.AvaI18n;
  const state = {
    status: null,
    view: "overview",
    streaming: false,
    eventSource: null,
    settings: null,
    settingsDirty: false,
    settingsFocusGroup: null,
    schema: null,
    haSettings: null,
    autoSaveTimer: null,
    autoSaving: false,
    jsonSyncTimer: null,
    jsonEditing: false,
    jsonSuppress: false,
    peerProbed: false,
    meta: null,
    pendingBackup: null,
    transferPeersBound: false,
    logs: [],
    logsSerial: "",
    logsLive: false,
    logsTimer: null,
    logsBusy: false,
    logsLastFetchAt: 0,
    /** Abnormal ends pulled from /v1/logs — never polled, only on view open / Refresh. */
    incidents: [],
    incidentsBusy: false,
    incidentsLastFetchAt: 0,
    incidentsTally: null,
    screenTimer: null,
    screenFailStreak: 0,
    scrcpyLive: false,
    scrcpyStarting: false,
    scrcpyOwned: false,
    /** Focused device screen via local /v1/adb/screen poll (no peer :8888). */
    adbScreen: false,
    /** serial → last ADB telemetry snapshot for peer params tab. */
    peerTelemetryBySerial: Object.create(null),
    /** In-flight ADB telemetry serial (dedupe). */
    peerTelemetryInflight: "",
    /** Local hub without Shizuku/root — Accessibility takeScreenshot poll. */
    a11yScreen: false,
    /** Peer Ava agent oneshot poll via hub /v1/cluster/screen (no ADB). */
    clusterScreen: false,
    /** serials already shown the ADB-lost confirm this session. */
    adbLostPrompted: new Set(),
    /** serials the user declined to clean; toast when auto-removed. */
    adbLostDeferred: new Set(),
    /** serials that were live ADB in the last poll. */
    adbKnownLive: new Set(),
    /** Debounce RMB / contextmenu → Android Back. */
    screenBackAt: 0,
    shellBusy: false,
    term: null,
    termFit: null,
    termLine: "",
    termCursor: 0,
    termHistory: [],
    termHistIdx: -1,
    termCwd: "/",
    termBusy: false,
    focusDeviceId: null,
    deviceTab: "params",
    wallThumbTimer: null,
    wallThumbBusy: false,
    /** True when a refresh was skipped while busy — run again after current pass. */
    wallThumbPending: false,
    wallSig: "",
    wallClockTimer: null,
    deviceViewMode: "grid",
    screenFetchGen: 0,
    appsList: [],
    appsFilter: "user",
    appsQuery: "",
    appsSerial: "",
    appsSelected: null,
    appsBusy: false,
    appsPage: 1,
    appsPageSize: 20,
    /** Online Mod Store catalog (browser → GitHub/raw, not device /v1 API). */
    modStore: {
      baseUrl: "",
      mods: [],
      status: "idle",
      error: "",
      downloading: Object.create(null),
    },
    filesSerial: "",
    filesPath: "/sdcard",
    filesHome: "/sdcard",
    filesBusy: false,
    filesEntries: [],
    filesSort: "name",
    filesSortAsc: true,
    /** Live ADB peers merged into wall/list (from GET /v1/adb/devices). */
    adbFleet: [],
    adbFleetAt: 0,
    /** hostPort → last auto-connect attempt ms (throttle). */
    adbAutoConnectAt: Object.create(null),
    /** In-flight auto-connect hostPort. */
    adbAutoConnecting: "",
    /** serial → last oneshot screen fetch ms. */
    adbShotAt: Object.create(null),
    /** serial → last wall uptime probe ms (throttle). */
    adbUptimeAt: Object.create(null),
    /** serial|id → "fetching" | "ok" | "fail" for wall OSD uptime. */
    adbUptimePhase: Object.create(null),
    /**
     * Entering peer params requires one full ADB telemetry pull.
     * Cleared after a successful pull for this visit; set again on re-enter.
     */
    peerParamsNeedFull: false,
    prefs: null,
    consoleUnlocked: false,
    /** User locked this tab — require password form; do not auto-probe prefs. */
    consoleLocked: false,
    /** Auth probe succeeded this session — blocks accidental login-form paints. */
    authPassed: false,
    lastActiveAt: Date.now(),
    autoLockTimer: null,
    booted: false,
    /** false until auth gate passes — no SSE / status paint / refresh loop. */
    live: false,
    /** Cached from /v1/status.auth.required (null = unknown). */
    authRequired: null,
    refreshTimer: null,
  };

  /** Device wall: oneshot screenshot cadence (server soft-cache covers most hits). */
  const WALL_THUMB_MS = 45_000;
  /** Parallel wall thumbs — keep low to avoid hammering hub / peer capture. */
  const WALL_THUMB_CONCURRENCY = 2;
  /** Per-URL abort so one hung screencap cannot pin wallThumbBusy forever. */
  const WALL_THUMB_FETCH_MS = 14_000;
  /** Persist last wall JPEG per device (IndexedDB; memory hot cache). v3 = sharper first cover. */
  const WALL_SHOT_DB = "ava-fleet-wall-shots-v3";
  const WALL_SHOT_STORE = "shots";
  const WALL_SHOT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
  /** Periodic wall refresh — light encode. */
  const WALL_SHOT_MAX_W = 560;
  const WALL_SHOT_QUALITY = 48;
  /** First cover on a tile — sharper; still within hub/peer maxWidth 960. */
  const WALL_SHOT_FIRST_MAX_W = 960;
  const WALL_SHOT_FIRST_QUALITY = 72;
  /** @type {Map<string, { dataUrl: string, at: number }>} */
  const wallShotMem = new Map();
  let wallShotDbPromise = null;

  const VIEWS = ["overview", "devices", "device", "settings", "files", "apps", "activity", "console"];

  /** Groups mirror Ava in-app settings home (SettingsScreen). */
  const SETTINGS_GROUPS = [
    {
      id: "voice",
      partitions: ["voice_satellite", "microphone"],
      open: false,
    },
    {
      id: "extensions",
      // Sendspin lives on Media Player (Interaction → Playback), not Voice Config.
      partitions: ["player", "sendspin", "mass_api", "notification", "local_scenes", "quick_entity"],
      open: false,
    },
    {
      id: "service",
      partitions: ["sidebar", "home_lock", "update", "settings_style"],
      open: false,
    },
    {
      id: "bluetooth",
      partitions: ["bluetooth"],
      open: false,
    },
    {
      id: "screensaver",
      partitions: ["screensaver"],
      open: false,
    },
    {
      id: "browser",
      partitions: ["browser", "ha"],
      open: false,
    },
    {
      id: "advanced",
      // camera / intent / cluster remounted as modules; leftover experimental appended.
      partitions: [],
      open: false,
    },
    {
      id: "modstore",
      partitions: ["mods"],
      open: false,
    },
  ];
  const SETTINGS_GROUP_PARTS = new Set(SETTINGS_GROUPS.flatMap((g) => g.partitions));
  const LOGS_LIMIT = 200;
  const LOGS_POLL_MS = 2500;
  const LOGS_CLIENT_THROTTLE_MS = 1200;
  /** Abnormal ends: one pass per view open, a handful of devices, no timer. */
  const INCIDENTS_THROTTLE_MS = 15000;
  const INCIDENTS_MAX_DEVICES = 6;
  const INCIDENTS_MAX_ROWS = 40;
  const PREFS_KEY = "ava.fleet.console.prefs";
  const UNLOCK_KEY = "ava.fleet.console.unlocked";
  /** Explicit lock (Lock now / auto-lock) — blocks silent auto-unlock with saved password. */
  const LOCKED_KEY = "ava.fleet.console.locked";
  /**
   * Plain password users type. Wire token is fixed 16-char Base64 of 12 zero-padded UTF-8 bytes.
   * Must match FleetPasswordCodec on device:
   *   "1234" → "MTIzNAAAAAAAAAAA"
   */
  const DEFAULT_FLEET_PASSWORD = "1234";
  const DEFAULT_FLEET_TOKEN = "MTIzNAAAAAAAAAAA";
  const DEFAULT_PREFS = {
    pin: DEFAULT_FLEET_PASSWORD,
    lockEnabled: true,
    theme: "dark",
    density: "comfortable",
    landing: "overview",
    lightweight: false,
    liveUpdates: true,
    showLogsNav: true,
    showTransfer: true,
    reduceMotion: false,
    autoLockMinutes: 0,
    /** Local plain password only — never the wire token. */
    fleetToken: DEFAULT_FLEET_PASSWORD,
  };

  const $ = (id) => document.getElementById(id);
  const rawFetch = window.fetch.bind(window);

  /** Motion UMD CDN — skip when missing or user/OS asks for reduced motion. */
  function motionLib() {
    const M = window.Motion;
    if (M && typeof M.animate === "function") return M;
    if (M?.default && typeof M.default.animate === "function") return M.default;
    return null;
  }

  function motionEnabled() {
    if (!motionLib()) return false;
    if ((state.prefs || DEFAULT_PREFS).reduceMotion) return false;
    if (document.body.classList.contains("pref-reduce-motion")) return false;
    try {
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return false;
    } catch (_) {}
    return true;
  }

  function motionAnimate(targets, keyframes, options) {
    const M = motionEnabled() ? motionLib() : null;
    if (!M || !targets) return null;
    try {
      return M.animate(targets, keyframes, options);
    } catch (_) {
      return null;
    }
  }

  function motionClearInline(el) {
    if (!el || !el.style) return;
    el.style.opacity = "";
    el.style.transform = "";
  }

  function animateViewEnter(view) {
    const el = $(`view-${view}`);
    if (!el || el.classList.contains("hidden")) return;
    motionAnimate(el, { opacity: [0, 1], y: [18, 0] }, {
      duration: 0.36,
      easing: [0.22, 1, 0.36, 1],
    });
    const title = $("pageTitle");
    if (title) {
      motionAnimate(title, { opacity: [0, 1], y: [6, 0] }, {
        duration: 0.28,
        easing: [0.22, 1, 0.36, 1],
      });
    }
  }

  function animateWallEnter(wallEl) {
    if (!wallEl) return;
    const tiles = Array.from(wallEl.querySelectorAll(".dev-tile"));
    if (!tiles.length) return;
    const M = motionLib();
    const delay = typeof M?.stagger === "function"
      ? M.stagger(0.04, { startDelay: 0.04 })
      : 0;
    motionAnimate(tiles, { opacity: [0, 1], y: [16, 0], scale: [0.96, 1] }, {
      delay,
      duration: 0.42,
      easing: [0.22, 1, 0.36, 1],
    });
  }

  function animatePanelEnter(panel) {
    if (!panel || panel.classList.contains("hidden")) return;
    motionAnimate(panel, { opacity: [0, 1], y: [8, 0] }, {
      duration: 0.22,
      easing: [0.22, 1, 0.36, 1],
    });
  }

  function modalPanel(modal) {
    if (!modal) return null;
    return modal.querySelector(".settings-modal-content, .apps-detail-dialog") || null;
  }

  function animateModalIn(modal) {
    if (!modal) return;
    const panel = modalPanel(modal);
    motionAnimate(modal, { opacity: [0, 1] }, { duration: 0.18 });
    if (panel) {
      motionAnimate(panel, { opacity: [0, 1], y: [14, 0], scale: [0.98, 1] }, {
        duration: 0.3,
        easing: [0.22, 1, 0.36, 1],
      });
    }
  }

  function animateModalOut(modal) {
    const M = motionEnabled() ? motionLib() : null;
    if (!modal || !M) return Promise.resolve();
    const panel = modalPanel(modal);
    try {
      const jobs = [M.animate(modal, { opacity: [1, 0] }, { duration: 0.14 })];
      if (panel) {
        jobs.push(M.animate(panel, {
          opacity: [1, 0],
          y: [0, 8],
          scale: [1, 0.985],
        }, { duration: 0.14 }));
      }
      return Promise.all(jobs.map((a) => (a && a.finished) || Promise.resolve())).then(() => {
        motionClearInline(modal);
        motionClearInline(panel);
      });
    } catch (_) {
      return Promise.resolve();
    }
  }

  /** Plain password from localStorage (default 1234). */
  function fleetPassword() {
    const raw = String((state.prefs || DEFAULT_PREFS).fleetToken || "").trim();
    if (!raw) return DEFAULT_FLEET_PASSWORD;
    // Migrated prefs may have stored the 16-char wire token — reverse it.
    if (raw.length === 16 && raw === DEFAULT_FLEET_TOKEN) return DEFAULT_FLEET_PASSWORD;
    if (raw.length === 16 && /^[A-Za-z0-9+/=]+$/.test(raw)) {
      const plain = decodeFleetToken(raw);
      return plain || DEFAULT_FLEET_PASSWORD;
    }
    return raw;
  }

  /**
   * password → fixed 16-char Base64 wire token (reversible).
   * Same algorithm as Kotlin FleetPasswordCodec.encode.
   */
  function encodeFleetToken(password) {
    const pwd = String(password ?? DEFAULT_FLEET_PASSWORD);
    const enc = new TextEncoder().encode(pwd);
    const padded = new Uint8Array(12);
    padded.set(enc.subarray(0, Math.min(enc.length, 12)));
    let bin = "";
    for (let i = 0; i < 12; i++) bin += String.fromCharCode(padded[i]);
    return btoa(bin);
  }

  /** 16-char wire token → plain password. */
  function decodeFleetToken(token) {
    try {
      const bin = atob(String(token || "").trim());
      let end = bin.length;
      while (end > 0 && bin.charCodeAt(end - 1) === 0) end -= 1;
      return bin.slice(0, end);
    } catch (_) {
      return "";
    }
  }

  /**
   * Header secret sent to the device.
   * Prefer plain password — every APK that checks "1234" accepts it;
   * new APKs also accept / prefer the 16-char wire token via matchesPassword().
   */
  function fleetWireToken() {
    const pwd = fleetPassword();
    if (state._fleetAuthMode === "wire") return encodeFleetToken(pwd);
    return pwd;
  }

  /** Query param: URI-encode plain password (device accepts plain or wire). */
  function encodeFleetPassword(password) {
    return encodeURIComponent(String(password ?? fleetPassword()).trim() || DEFAULT_FLEET_PASSWORD);
  }

  /** Inject X-Ava-Fleet-Token for /v1 APIs. */
  async function fetch(input, init) {
    const opts = init ? { ...init } : {};
    const url = typeof input === "string" ? input : (input && input.url) || "";
    const token = fleetWireToken();
    if (token && (url.startsWith("/v1/") || /\/v1\//.test(url) || url.includes(":8888/"))) {
      const headers = new Headers(opts.headers || (typeof Request !== "undefined" && input instanceof Request ? input.headers : undefined) || {});
      if (!headers.has("X-Ava-Fleet-Token")) headers.set("X-Ava-Fleet-Token", token);
      opts.headers = headers;
    }
    const res = await rawFetch(input, opts);
    if (res.status === 401) {
      const meta = $("liveBadge")?.querySelector(".live-label");
      if (meta) meta.textContent = t("authNeeded");
      const hint = $("fleetTokenHint");
      if (hint) hint.textContent = t("authNeededHint");
      lockConsole({ resetInput: false });
    }
    return res;
  }

  /** Pull ?password= from the address bar — plain or 16-char token. */
  function bootstrapFleetTokenFromUrl() {
    try {
      const u = new URL(window.location.href);
      const tok = (
        u.searchParams.get("password") ||
        u.searchParams.get("token") ||
        u.searchParams.get("fleetToken") ||
        ""
      ).trim();
      if (!tok) return false;
      let plain = tok;
      if (tok.length === 16 && /^[A-Za-z0-9+/=]+$/.test(tok)) {
        plain = decodeFleetToken(tok) || DEFAULT_FLEET_PASSWORD;
      }
      state.prefs = { ...(state.prefs || DEFAULT_PREFS), fleetToken: plain };
      try {
        localStorage.setItem(PREFS_KEY, JSON.stringify(state.prefs));
      } catch (_) {}
      u.searchParams.delete("password");
      u.searchParams.delete("token");
      u.searchParams.delete("fleetToken");
      const qs = u.searchParams.toString();
      history.replaceState(null, "", u.pathname + (qs ? `?${qs}` : "") + u.hash);
      setUnlocked(true);
      return true;
    } catch (_) {
      return false;
    }
  }

  function setAuthBannerVisible(show) {
    const banner = $("authBanner");
    if (!banner) return;
    // Auth banner is for in-console reauth only — never while the gate is up.
    banner.classList.toggle("hidden", !show || !state.live);
  }

  function deviceAuthRequired() {
    if (state.authRequired != null) return !!state.authRequired;
    return !!(state.status && state.status.auth && state.status.auth.required);
  }

  async function probeFleetPassword(password) {
    const pwd = String(password || "").trim() || DEFAULT_FLEET_PASSWORD;
    const wire = encodeFleetToken(pwd);
    // Plain first (legacy APKs), then 16-char wire. Prefer /v1/auth/check (lightweight).
    const paths = ["/v1/auth/check", "/v1/settings"];
    for (const secret of [pwd, wire]) {
      for (const path of paths) {
        try {
          const res = await rawFetch(path, {
            cache: "no-store",
            headers: { "X-Ava-Fleet-Token": secret },
          });
          if (res.status === 404 && path === "/v1/auth/check") continue;
          if (res.status === 401) break; // wrong secret — try next
          if (res.ok) {
            state._fleetAuthMode = secret === wire ? "wire" : "plain";
            return true;
          }
        } catch (_) {}
      }
    }
    return false;
  }

  /** Lightweight auth probe — stores status but does not paint the dashboard. */
  async function probeAuthRequired() {
    try {
      const res = await rawFetch("/v1/status", { cache: "no-store" });
      if (!res.ok) {
        state.authRequired = true;
        return true;
      }
      const data = await res.json();
      state.status = data;
      state.authRequired = !!(data.auth && data.auth.required);
      return state.authRequired;
    } catch (_) {
      state.authRequired = true;
      return true;
    }
  }

  function isUnlocked() {
    if (isSessionLocked()) return false;
    if (state.authRequired === false) return true;
    if (state.status && !deviceAuthRequired()) return true;
    try {
      return sessionStorage.getItem(UNLOCK_KEY) === "1";
    } catch (_) {
      return !!state.consoleUnlocked;
    }
  }

  function isSessionLocked() {
    if (state.consoleLocked) return true;
    try {
      return sessionStorage.getItem(LOCKED_KEY) === "1";
    } catch (_) {
      return false;
    }
  }

  function setSessionLocked(on) {
    state.consoleLocked = !!on;
    try {
      if (on) sessionStorage.setItem(LOCKED_KEY, "1");
      else sessionStorage.removeItem(LOCKED_KEY);
    } catch (_) {}
  }

  function setUnlocked(ok) {
    state.consoleUnlocked = !!ok;
    try {
      if (ok) sessionStorage.setItem(UNLOCK_KEY, "1");
      else sessionStorage.removeItem(UNLOCK_KEY);
    } catch (_) {}
  }

  function clearLockError() {
    const err = $("consoleLockError");
    if (!err) return;
    err.textContent = "";
    err.classList.add("hidden");
  }

  function showLockError(msg) {
    const err = $("consoleLockError");
    if (!err) return;
    err.textContent = msg || t("consoleLockBad");
    err.classList.remove("hidden");
  }

  /**
   * Lock subtitle: show factory-password hint only when we know defaultPassword===true.
   * Unknown / changed → keep empty (never skeleton under the title).
   */
  function syncLockSub() {
    const sub = $("consoleLockSub");
    if (!sub) return;
    const flag = state.status?.auth?.defaultPassword;
    if (flag === true) {
      sub.textContent = t("consoleLockSub");
      sub.classList.remove("is-empty");
      sub.setAttribute("aria-hidden", "false");
      return;
    }
    sub.textContent = "";
    sub.classList.add("is-empty");
    sub.setAttribute("aria-hidden", "true");
  }

  function setLockI18nVeil(on) {
    const gate = $("consoleLockGate");
    const veil = $("consoleLockI18nVeil");
    if (!gate) return;
    gate.classList.toggle("is-i18n-loading", !!on);
    if (veil) {
      veil.hidden = !on;
      veil.setAttribute("aria-hidden", on ? "false" : "true");
    }
  }

  /** @param {"booting"|"login"} mode */
  function setLockGateMode(mode, opts = {}) {
    const gate = $("consoleLockGate");
    const form = $("consoleLockForm");
    if (!gate) return;
    // Once live / auto-unlocked, never flip into the password form — unless forcing a lock.
    if (
      mode === "login" &&
      !opts.force &&
      !isSessionLocked() &&
      (state.live || state.authPassed)
    ) {
      return;
    }
    const booting = mode === "booting";
    gate.classList.toggle("is-booting", booting);
    gate.classList.toggle("is-login", !booting);
    gate.setAttribute("aria-busy", booting ? "true" : "false");
    if (form) {
      form.hidden = booting;
      form.setAttribute("aria-hidden", booting ? "true" : "false");
    }
    const bootText = $("consoleLockBootText");
    if (bootText && booting) bootText.textContent = t("consoleBooting");
    if (!booting) setLockI18nVeil(false);
  }

  function showLockGate(show, opts = {}) {
    const gate = $("consoleLockGate");
    if (!gate) return;
    const wasVisible = !gate.classList.contains("hidden");
    gate.classList.toggle("hidden", !show);
    document.body.classList.toggle("console-locked", !!show);
    if (!show) {
      setLockI18nVeil(false);
      return;
    }
    const mode = opts.mode || "booting";
    setLockGateMode(mode, { force: !!opts.force });
    if (mode === "login") syncLockSub();
    const input = $("consoleLockInput");
    if (opts.resetInput && input) {
      input.value = "";
      clearLockError();
    }
    if (mode === "login" && (!wasVisible || opts.focus) && input) {
      setTimeout(() => {
        if (!gate.classList.contains("hidden") && gate.classList.contains("is-login")) {
          input.focus();
        }
      }, 50);
    }
  }

  /** Tear down live console (SSE / poll / thumbs). Login gate owns the screen. */
  function stopConsoleLive() {
    state.live = false;
    state.authPassed = false;
    try { state.eventSource?.close(); } catch (_) {}
    state.eventSource = null;
    if (state.refreshTimer) {
      clearInterval(state.refreshTimer);
      state.refreshTimer = null;
    }
    try { stopWallThumbLoop(); } catch (_) {}
    try { stopLogsPolling(); } catch (_) {}
    if (state.streaming) {
      try { stopScreen(); } catch (_) {}
    }
  }

  /** Start live console exactly once after auth succeeds. */
  function startConsoleLive() {
    setSessionLocked(false);
    state.authPassed = true;
    // Hide gate first so the password form never paints for a frame.
    showLockGate(false);
    setLockGateMode("booting"); // reset DOM for next lock; gate already hidden
    setAuthBannerVisible(false);
    if (state.live) {
      if (state.status) paintDashboard(state.status);
      return;
    }
    state.live = true;
    const landing = (state.prefs && state.prefs.landing) || "overview";
    showView(["overview", "settings"].includes(landing) ? landing : "overview");
    refresh();
    if ((state.prefs || DEFAULT_PREFS).liveUpdates) connectEvents();
    if (!state.refreshTimer) {
      state.refreshTimer = setInterval(() => {
        if (state.live) refresh();
      }, 30000);
    }
    setupAutoLock();
  }

  /** Drop back to the login gate; do not keep the dashboard alive underneath. */
  function lockConsole(opts = {}) {
    document.documentElement.classList.remove("auth-resume");
    // Persist lock for this tab so refresh cannot silent-auto-unlock with saved password.
    setSessionLocked(true);
    setUnlocked(false);
    state.authPassed = false;
    stopConsoleLive();
    showLockGate(true, {
      mode: "login",
      force: true,
      resetInput: !!opts.resetInput,
      focus: opts.focus !== false,
    });
  }

  /**
   * Boot: keep shell visible (connecting). Never paint the lock gate unless
   * the probe proves we need a password — kills the logged-in → login flash.
   * Explicit session lock always stays on the password form.
   */
  async function resolveAuthAndStart() {
    state.authPassed = false;
    document.documentElement.classList.remove("auth-resume");

    if (isSessionLocked()) {
      showLockGate(true, { mode: "login", force: true, focus: true });
      return;
    }

    // Stay on shell; do not showLockGate(true) during probe.
    showLockGate(false);
    setLiveState("connecting");

    try {
      const required = await probeAuthRequired();
      if (!required) {
        setUnlocked(true);
        startConsoleLive();
        return;
      }
      const pwd = fleetPassword();
      if (await probeFleetPassword(pwd)) {
        savePrefs({ fleetToken: pwd });
        setUnlocked(true);
        startConsoleLive();
        return;
      }
    } catch (err) {
      console.warn("auth probe:", err);
    }

    // Need user input — only failure path reveals the password form.
    if (state.live || state.authPassed) return;
    setUnlocked(false);
    showLockGate(true, { mode: "login", force: true, focus: true });
  }

  /** Language switch: whole-card skeleton veil; never flash login ↔ boot. */
  function applyI18nWithTransition() {
    const gate = $("consoleLockGate");
    const locked = !!(gate && !gate.classList.contains("hidden"));

    if (locked) {
      const wasLogin = gate.classList.contains("is-login");
      setLockI18nVeil(true);
      // Double-rAF: paint veil before swapping strings.
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          applyStaticI18n();
          if (state.live || state.authPassed) {
            showLockGate(false);
            setLockI18nVeil(false);
            return;
          }
          if (wasLogin) {
            setLockGateMode("login");
            syncLockSub();
          } else {
            setLockGateMode("booting");
          }
          window.setTimeout(() => setLockI18nVeil(false), 120);
        });
      });
      return;
    }

    document.body.classList.add("i18n-swapping");
    applyStaticI18n();
    window.setTimeout(() => document.body.classList.remove("i18n-swapping"), 160);
  }

  function syncAuthBannerFromStatus(s) {
    if (!state.live) {
      setAuthBannerVisible(false);
      return;
    }
    const required = !!(s && s.auth && s.auth.required);
    if (required && !isUnlocked()) setAuthBannerVisible(true);
    else setAuthBannerVisible(false);
  }

  function loadPrefs() {
    try {
      const raw = localStorage.getItem(PREFS_KEY);
      if (!raw) return { ...DEFAULT_PREFS };
      const merged = { ...DEFAULT_PREFS, ...JSON.parse(raw) };
      if (!String(merged.fleetToken || "").trim()) {
        merged.fleetToken = DEFAULT_FLEET_PASSWORD;
      }
      return merged;
    } catch (_) {
      return { ...DEFAULT_PREFS };
    }
  }

  function savePrefs(next) {
    state.prefs = { ...DEFAULT_PREFS, ...(state.prefs || {}), ...next };
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(state.prefs));
    } catch (_) {}
    applyPrefs();
  }

  function resolveTheme(theme) {
    if (theme === "system") {
      return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
    }
    return theme === "light" ? "light" : "dark";
  }

  /** Apply resolved light/dark once; skip DOM writes when unchanged (no flash). */
  function applyResolvedTheme(resolved) {
    const next = resolved === "light" ? "light" : "dark";
    const root = document.documentElement;
    const body = document.body;
    const cur = (body && body.dataset.theme) || root.dataset.theme || "";
    if (cur === next && root.dataset.theme === next) {
      return next;
    }
    root.dataset.theme = next;
    root.style.colorScheme = next;
    if (body) body.dataset.theme = next;
    const meta = document.getElementById("colorSchemeMeta");
    if (meta) meta.setAttribute("content", next);
    try {
      localStorage.setItem("ava-fleet-theme", next);
    } catch (_) {}
    return next;
  }

  function setTheme(theme) {
    const mode = theme === "system" || theme === "light" || theme === "dark" ? theme : "dark";
    savePrefs({ theme: mode });
  }

  function touchActivity() {
    state.lastActiveAt = Date.now();
  }

  function applyPrefs() {
    const p = state.prefs || DEFAULT_PREFS;
    applyResolvedTheme(resolveTheme(p.theme));
    document.body.classList.toggle("pref-lightweight", !!p.lightweight);
    document.body.classList.toggle("pref-compact", p.density === "compact");
    document.body.classList.toggle("pref-reduce-motion", !!p.reduceMotion);

    document.querySelectorAll('.nav-item[data-view="activity"]').forEach((el) => {
      el.classList.toggle("hidden", !p.showLogsNav);
    });

    const transferBtn = $("settingsTransferBtn");
    if (transferBtn) transferBtn.classList.toggle("hidden", !p.showTransfer);

    const langSel = $("consoleLangSelect");
    if (langSel) langSel.value = getLang();
    const themeSel = $("consoleThemeSelect");
    if (themeSel) themeSel.value = p.theme || "dark";
    const dens = $("consoleDensitySelect");
    if (dens) dens.value = p.density || "comfortable";
    const land = $("consoleLandingSelect");
    if (land) land.value = p.landing || "overview";
    const auto = $("consoleAutoLockSelect");
    if (auto) auto.value = String(p.autoLockMinutes || 0);
    const tokenInput = $("consoleFleetToken");
    if (tokenInput && document.activeElement !== tokenInput) {
      tokenInput.value = fleetPassword();
    }
    const required = deviceAuthRequired();
    const pinStatus = $("consolePinStatus");
    if (pinStatus) {
      pinStatus.textContent = state.live
        ? (required ? t("fleetPasswordOnDevice") : t("fleetPasswordOpenLan"))
        : "";
    }

    const map = {
      prefLightweight: "lightweight",
      prefLiveUpdates: "liveUpdates",
      prefShowLogsNav: "showLogsNav",
      prefShowTransfer: "showTransfer",
      prefReduceMotion: "reduceMotion",
    };
    Object.entries(map).forEach(([id, key]) => {
      const el = $(id);
      if (el) el.checked = !!p[key];
    });

    if (state.booted && state.live) {
      if (p.liveUpdates) {
        if (!state.eventSource || state.eventSource.readyState === 2) connectEvents();
      } else if (state.eventSource) {
        try { state.eventSource.close(); } catch (_) {}
        state.eventSource = null;
      }
    }

    if (state.live) setupAutoLock();
  }

  function setupAutoLock() {
    if (state.autoLockTimer) {
      clearInterval(state.autoLockTimer);
      state.autoLockTimer = null;
    }
    const mins = Number((state.prefs || {}).autoLockMinutes || 0);
    if (!mins || !deviceAuthRequired() || !state.live) return;
    state.autoLockTimer = setInterval(() => {
      if (!state.live || !isUnlocked()) return;
      if (Date.now() - state.lastActiveAt > mins * 60_000) {
        lockConsole({ resetInput: true, focus: true });
      }
    }, 15_000);
  }

  async function tryUnlock() {
    const input = $("consoleLockInput");
    const password = (input?.value || "").trim() || DEFAULT_FLEET_PASSWORD;
    const ok = await probeFleetPassword(password);
    if (ok) {
      savePrefs({ fleetToken: password });
      setSessionLocked(false);
      setUnlocked(true);
      touchActivity();
      clearLockError();
      startConsoleLive();
      if (state.view === "settings") loadSettings();
      return true;
    }
    showLockError(t("consoleLockBad"));
    if (input) {
      input.select();
      input.focus();
    }
    return false;
  }

  function utf8ByteLength(str) {
    try {
      return new TextEncoder().encode(String(str || "")).length;
    } catch (_) {
      return String(str || "").length;
    }
  }

  /** Write access password to device DataStore + keep browser prefs in sync. */
  async function saveDeviceAccessPassword() {
    const input = $("consoleFleetToken");
    const statusEl = $("consolePinStatus");
    const plain = String(input?.value || "").trim();
    if (plain.length < 4) {
      flash(statusEl, t("fleetPasswordTooShort"), true);
      input?.focus();
      return false;
    }
    if (utf8ByteLength(plain) > 12) {
      flash(statusEl, t("fleetPasswordTooLong"), true);
      input?.focus();
      return false;
    }
    if (!state.live) {
      flash(statusEl, t("fleetPasswordNeedLive"), true);
      return false;
    }
    flash(statusEl, t("fleetPasswordSaving"), false);
    try {
      const wire = encodeFleetToken(plain);
      const res = await fetch("/v1/settings/apply", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          settings: { experimental: { clusterAccessToken: wire } },
        }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json().catch(() => ({}));
      if (data && data.ok === false) throw new Error(data.error || "apply_failed");
      savePrefs({ fleetToken: plain });
      if (state.settings && state.settings.experimental) {
        state.settings.experimental.clusterAccessToken = wire;
      }
      flash(statusEl, t("fleetPasswordSaved"), false);
      return true;
    } catch (err) {
      console.warn(err);
      flash(statusEl, t("fleetPasswordSaveError"), true);
      return false;
    }
  }

  function initTheme() {
    state.prefs = loadPrefs();
    try {
      const old = localStorage.getItem("ava-fleet-theme");
      if (old && !localStorage.getItem(PREFS_KEY)) {
        state.prefs.theme = old === "light" ? "light" : "dark";
      }
    } catch (_) {}
    // Align with head bootstrap — only write if prefs resolve differently.
    applyResolvedTheme(resolveTheme(state.prefs.theme));
  }

  function applyStaticI18n() {
    document.title = t("docTitle");
    document.querySelectorAll("[data-i18n]").forEach((el) => {
      const key = el.getAttribute("data-i18n");
      if (key) el.textContent = t(key);
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
      const key = el.getAttribute("data-i18n-placeholder");
      if (key) el.setAttribute("placeholder", t(key));
    });
    document.querySelectorAll("[data-i18n-title]").forEach((el) => {
      const key = el.getAttribute("data-i18n-title");
      if (key) el.setAttribute("title", t(key));
    });
    document.querySelectorAll("[data-i18n-aria-label]").forEach((el) => {
      const key = el.getAttribute("data-i18n-aria-label");
      if (key) el.setAttribute("aria-label", t(key));
    });
    const sel = $("consoleLangSelect");
    if (sel) sel.value = getLang();
    renderIncidents();
    showView(state.view, true);
    if (state.live && state.status) render(state.status);
    else if (!state.live) setLiveState("connecting");
    applyPrefs();
    if (state.live && state.settings) {
      renderSettingsForm({ syncJson: false, preserveOpen: true });
      setJsonSyncStatus(state.jsonEditing ? "editing" : (state.settingsDirty ? "dirty" : "synced"));
    }
    enhanceSelects();
    if ($("consoleLockGate")?.classList.contains("is-login")) syncLockSub();
    const bootText = $("consoleLockBootText");
    if (bootText && $("consoleLockGate")?.classList.contains("is-booting")) {
      bootText.textContent = t("consoleBooting");
    }
  }

  function showView(view, keepOnlyTitle) {
    if (view !== "apps" && state.appsSelected) closeAppsDetailModal();
    if (view !== "activity") closeIncidentModal();
    if (view === "activity" && state.prefs && !state.prefs.showLogsNav) {
      view = "overview";
    }
    const switched = state.view !== view;
    state.view = view;
    VIEWS.forEach((name) => {
      const el = $(`view-${name}`);
      if (el) el.classList.toggle("hidden", view !== name);
    });
    $("settingsMiniNav")?.classList.toggle("hidden", view !== "settings");
    document.body.classList.toggle("view-settings", view === "settings");
    if (view !== "settings") document.body.classList.remove("settings-topbar-away");
    document.querySelectorAll(".nav-item[data-view]").forEach((btn) => {
      const navView = btn.dataset.view;
      btn.classList.toggle("on", navView === view || (view === "device" && navView === "overview"));
    });
    const titles = {
      overview: ["overviewTitle", "overviewSub"],
      devices: ["devicesTitle", "devicesSub"],
      device: ["deviceTitle", "deviceSub"],
      settings: ["settingsTitle", "settingsSub"],
      files: ["filesTitle", "filesSub"],
      apps: ["appsTitle", "appsSub"],
      activity: ["logsTitle", "logsSub"],
      console: ["consoleTitle", "consoleSub"],
    };
    const pair = titles[view] || titles.overview;
    $("pageTitle").textContent = t(pair[0]);
    $("pageSub").textContent = t(pair[1]);
    if (!keepOnlyTitle && view !== "device" && state.streaming) {
      stopScreen();
    }
    if (view === "settings" && !state.settings && state.live) {
      loadSettings();
    }
    if (view === "settings") {
      document.body.classList.remove("settings-topbar-away");
      ensureSettingsStickyMetricsHook();
      requestAnimationFrame(() => {
        updateSettingsStickyMetrics();
        bindSettingsScrollSpy({ force: true });
        updateSettingsNavFromScroll();
      });
    }
    if (view === "files") {
      // Use cache on tab switch — only the refresh button forces a reload.
      void loadFilesView(false);
    }
    if (view === "apps") {
      // Use cache on tab switch — refresh / filter / device change still force.
      void loadAppsView(false);
    }
    if (view === "console") {
      applyPrefs();
    }
    if (view === "activity" && state.live) {
      void (async () => {
        await refreshAdbDeviceOptions();
        await refreshLogs();
        if (state.logsLive) startLogsPolling();
        await refreshIncidents();
      })();
    } else {
      stopLogsPolling();
    }
    if (view === "device") {
      applyDeviceTab(state.deviceTab || "params");
    }
    syncOverviewWallChrome();
    if (view === "overview" && state.live && state.deviceViewMode !== "list") {
      startWallThumbLoop();
      startWallClock();
    } else {
      stopWallThumbLoop();
      stopWallClock();
    }
    if (switched) animateViewEnter(view);
  }

  function findDevice(id) {
    return fleetDevices(state.status).find((d) => d.id === id) || null;
  }

  function openDevice(id, tab) {
    state.focusDeviceId = id;
    state.deviceTab = tab === "screen" || tab === "terminal" ? tab : "params";
    // Opening the peer params page → mandatory one full telemetry pull.
    state.peerParamsNeedFull = state.deviceTab === "params";
    showView("device");
    renderDeviceFocus();
    applyDeviceTab(state.deviceTab);
    if (state.deviceTab !== "screen" && state.streaming) stopScreen();
    else if (state.deviceTab === "screen") stopScreen();
  }

  function appsSerialValue() {
    return ($("appsDeviceSelect")?.value || state.appsSerial || "").trim();
  }

  const APP_PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;

  /** A few Android builds prefix pm output with an apk path; keep only the real package id. */
  function normalizePackageName(raw) {
    const value = String(raw || "").trim();
    if (!value) return "";
    const parts = value.split("=");
    for (let i = parts.length - 1; i >= 0; i -= 1) {
      const candidate = parts[i].trim();
      if (APP_PACKAGE_RE.test(candidate)) return candidate;
    }
    return "";
  }

  function normalizeAppRecord(app) {
    const pkg = normalizePackageName(app?.package);
    return pkg ? { ...app, package: pkg, rawPackage: String(app.package || "") } : null;
  }

  function appsLoadingMarkup() {
    return `<span class="apps-loading" role="status"><span class="apps-spinner" aria-hidden="true"></span><span>${escapeHtml(t("appsLoading"))}</span></span>`;
  }

  function appsDeviceLabel(d) {
    const serial = d.serial || "";
    return d.model || d.product || serial || "—";
  }

  async function refreshAdbDeviceOptions() {
    try {
      await fetch("/v1/adb/start", { method: "POST" }).catch(() => {});
      const res = await fetch("/v1/adb/devices", { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      const localModel = state.status?.model || "";
      const raw = Array.isArray(j.devices) ? j.devices : [];
      const peers = raw.filter((d) => (d.state === "device" || !d.state) && !isAdbSelfDevice(d, localModel));
      let selfList = Array.isArray(j.selfDevices) ? j.selfDevices.slice() : [];
      if (!selfList.length && Array.isArray(j.selfSerials)) {
        selfList = j.selfSerials.filter(Boolean).map((serial) => ({
          serial,
          state: "device",
          model: localModel,
          self: true,
        }));
      }
      if (!selfList.length) {
        selfList = raw.filter((d) => isAdbSelfDevice(d, localModel));
      }
      if (!selfList.length) {
        const only = raw.filter((d) => d.state === "device" || !d.state);
        if (only.length === 1) selfList = only.map((d) => ({ ...d, self: true }));
      }
      const opts = [
        ...selfList.map((d) => ({
          serial: d.serial || "",
          label: appsDeviceLabel({ ...d, self: true }),
          local: true,
        })),
        ...peers.map((d) => ({
          serial: d.serial || "",
          label: appsDeviceLabel(d),
          local: false,
        })),
      ].filter((o) => o.serial);

      fillAdbDeviceSelect($("appsDeviceSelect"), opts, state.appsSerial, (v) => { state.appsSerial = v; });
      fillAdbDeviceSelect($("filesDeviceSelect"), opts, state.filesSerial, (v) => { state.filesSerial = v; });
      fillAdbDeviceSelect($("logsDeviceSelect"), opts, state.logsSerial, (v) => { state.logsSerial = v; });
    } catch (_) {
      const empty = `<option value="">${escapeHtml(t("appsNoDevice"))}</option>`;
      const appsSel = $("appsDeviceSelect");
      if (appsSel) {
        appsSel.innerHTML = empty;
        appsSel.value = "";
        enhanceSelect(appsSel);
      }
      state.appsSerial = "";
      const filesSel = $("filesDeviceSelect");
      if (filesSel) {
        filesSel.innerHTML = empty;
        filesSel.value = "";
        enhanceSelect(filesSel);
      }
      state.filesSerial = "";
      const logsSel = $("logsDeviceSelect");
      if (logsSel) {
        logsSel.innerHTML = empty;
        logsSel.value = "";
        enhanceSelect(logsSel);
      }
      state.logsSerial = "";
    }
  }

  function fillAdbDeviceSelect(sel, opts, prev, onValue) {
    if (!sel) return;
    if (!opts.length) {
      sel.innerHTML = `<option value="">${escapeHtml(t("appsNoDevice"))}</option>`;
      sel.value = "";
      onValue?.("");
      enhanceSelect(sel);
      return;
    }
    sel.innerHTML = opts.map((o) =>
      `<option value="${escapeHtml(o.serial)}"${o.local ? ' data-local="1"' : ""}>${escapeHtml(o.label)}</option>`,
    ).join("");
    const preferSelf = opts.find((o) => o.local)?.serial || opts[0].serial;
    if (prev && opts.some((o) => o.serial === prev)) sel.value = prev;
    else if (prev && opts.some((o) => o.serial === prev && !o.local)) sel.value = prev;
    else sel.value = preferSelf;
    onValue?.(sel.value || "");
    enhanceSelect(sel);
  }

  function filesSerialValue() {
    return ($("filesDeviceSelect")?.value || state.filesSerial || "").trim();
  }


  // ── File icons (inline SVG) ────────────────────────
  const FILES_ICON_FOLDER = '<svg viewBox="0 0 24 24" width="22" height="22"><path fill="#f59e0b" d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>';
  const FILES_ICON_FILE = '<svg viewBox="0 0 24 24" width="22" height="22"><path fill="none" stroke="#94a3b8" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline fill="none" stroke="#94a3b8" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" points="14 2 14 8 20 8"/></svg>';
  const FILES_ICON_DL = '<svg viewBox="0 0 24 24" width="16" height="16"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M12 4v10m0 0l-4-4m4 4l4-4M5 18h14"/></svg>';
  const FILES_ICON_DEL = '<svg viewBox="0 0 24 24" width="16" height="16"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14"/></svg>';

  function filesDownloadUrl(path) {
    const serial = filesSerialValue();
    const q = new URLSearchParams({ path: path || "", serial: serial || "" });
    // <a download> cannot send X-Ava-Fleet-Token — pass the same secret as ?token=.
    const tok = fleetWireToken();
    if (tok) q.set("token", tok);
    return `/v1/adb/files/download?${q}`;
  }

  async function filesTriggerDownload(url, name) {
    if (!url) return;
    showFleetToast(t("filesDownloading"), { key: "files-dl" });
    try {
      // Prefer fetch (injects auth header). Fall back to tokenized <a> navigation.
      const res = await fetch(url, { cache: "no-store" });
      if (!res.ok) {
        const j = await res.json().catch(() => ({}));
        throw new Error(j.error || `HTTP ${res.status}`);
      }
      const blob = await res.blob();
      const obj = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = obj;
      a.download = name || "download";
      a.rel = "noopener";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(obj), 4000);
    } catch (e) {
      // Auth/header path failed — try bare navigation with ?token= already on url.
      try {
        const a = document.createElement("a");
        a.href = url;
        a.download = name || "download";
        a.rel = "noopener";
        document.body.appendChild(a);
        a.click();
        a.remove();
      } catch (_) {
        showFleetToast(String(e?.message || e || "download_failed"), { err: true, key: "files-dl" });
      }
    }
  }

  function filesHomePath() {
    const p = String(state.filesHome || "/sdcard").trim();
    return p || "/sdcard";
  }

  let _filesAbort = null;
  const FILES_CACHE_MAX = 24;
  /** Hard TTL: drop cache entry after this age. */
  const FILES_CACHE_TTL_MS = 10 * 60_000;
  /** Within this age, skip network entirely (no quiet revalidate). */
  const FILES_CACHE_SOFT_MS = 10 * 60_000;
  const FILES_PREFETCH_MAX = 3;
  /** @type {Map<string, { entries: any[], at: number }>} */
  const _filesCache = new Map();
  let _filesPrefetchTimer = 0;
  let _filesNavToken = 0;

  function filesCacheKey(serial, path) {
    return String(serial || "") + "\0" + String(path || "");
  }

  function filesCacheGet(serial, path) {
    const key = filesCacheKey(serial, path);
    const hit = _filesCache.get(key);
    if (!hit) return null;
    if (Date.now() - hit.at > FILES_CACHE_TTL_MS) {
      _filesCache.delete(key);
      return null;
    }
    // LRU touch
    _filesCache.delete(key);
    _filesCache.set(key, hit);
    return hit;
  }

  function filesCacheSet(serial, path, entries) {
    const key = filesCacheKey(serial, path);
    if (_filesCache.has(key)) _filesCache.delete(key);
    _filesCache.set(key, { entries: Array.isArray(entries) ? entries.slice() : [], at: Date.now() });
    while (_filesCache.size > FILES_CACHE_MAX) {
      const oldest = _filesCache.keys().next().value;
      _filesCache.delete(oldest);
    }
  }

  function filesCacheInvalidate(serial, path) {
    if (!serial) {
      _filesCache.clear();
      return;
    }
    if (path == null || path === "") {
      const prefix = String(serial) + "\0";
      for (const key of [..._filesCache.keys()]) {
        if (key.startsWith(prefix)) _filesCache.delete(key);
      }
      return;
    }
    const p = String(path).replace(/\/+$/, "") || "/";
    _filesCache.delete(filesCacheKey(serial, p));
    _filesCache.delete(filesCacheKey(serial, path));
    // Also drop parent so listing refreshes after delete/mkdir/upload
    const idx = p.lastIndexOf("/");
    const parent = idx <= 0 ? "/" : p.substring(0, idx);
    _filesCache.delete(filesCacheKey(serial, parent));
    if (p !== "/sdcard") _filesCache.delete(filesCacheKey(serial, "/sdcard"));
  }

  async function filesFetchListRaw(serial, path, signal) {
    const q = new URLSearchParams({ serial, path });
    const res = await fetch(`/v1/adb/files/list?${q}`, { cache: "no-store", signal });
    const j = await res.json().catch(() => ({}));
    return Array.isArray(j.entries) ? j.entries : [];
  }

  async function filesFetchList(serial, path) {
    if (_filesAbort) { _filesAbort.abort(); _filesAbort = null; }
    const ac = new AbortController();
    _filesAbort = ac;
    return filesFetchListRaw(serial, path, ac.signal);
  }

  function filesSchedulePrefetch(serial, entries) {
    if (_filesPrefetchTimer) clearTimeout(_filesPrefetchTimer);
    const dirs = (entries || []).filter((e) => e && e.dir && e.path).slice(0, FILES_PREFETCH_MAX);
    if (!dirs.length) return;
    _filesPrefetchTimer = setTimeout(() => {
      _filesPrefetchTimer = 0;
      void (async () => {
        for (const d of dirs) {
          if (filesCacheGet(serial, d.path)) continue;
          try {
            const ac = new AbortController();
            const timer = setTimeout(() => ac.abort(), 12000);
            const list = await filesFetchListRaw(serial, d.path, ac.signal);
            clearTimeout(timer);
            filesCacheSet(serial, d.path, list);
          } catch (_) {
            /* prefetch best-effort */
          }
        }
      })();
    }, 280);
  }

  async function filesLoadEntries(serial, target) {
    let entries = await filesFetchList(serial, target);
    if (target === "/" && !entries.length) {
      entries = await filesFetchRoots(serial);
    }
    filesCacheSet(serial, target, entries);
    filesSchedulePrefetch(serial, entries);
    return entries;
  }

  function filesFormatSize(bytes) {
    if (!bytes || bytes <= 0) return "";
    const units = ["B", "KB", "MB", "GB"];
    let i = 0;
    let size = bytes;
    while (size >= 1024 && i < units.length - 1) { size /= 1024; i++; }
    return (i === 0 ? String(size) : size.toFixed(1)) + " " + units[i];
  }

  function filesShowBusy(show) {
    const el = $("filesLoading");
    if (el) el.classList.toggle("hidden", !show);
  }

  const FILES_PROTECTED_DIRS = new Set([
    "Android", "DCIM", "Download", "Downloads", "Documents", "Music",
    "Movies", "Pictures", "Ringtones", "Alarms", "Notifications",
    "Podcasts", "Audiobooks",
  ]);

  /** Mount / root paths that must never show a delete control. */
  function filesIsProtectedPath(path) {
    const p = String(path || "").replace(/\/+$/, "") || "/";
    if (
      p === "/" ||
      p === "/sdcard" ||
      p === "/storage" ||
      p === "/storage/emulated" ||
      p === "/mnt" ||
      p === "/mnt/media_rw"
    ) {
      return true;
    }
    // /storage/emulated/0 (and other user ids), /storage/<uuid>, /mnt/media_rw/<uuid>
    if (/^\/storage\/emulated\/\d+$/.test(p)) return true;
    if (/^\/storage\/[^/]+$/.test(p)) return true;
    if (/^\/mnt\/media_rw\/[^/]+$/.test(p)) return true;

    const parts = p.split("/").filter(Boolean);
    // Anything directly under filesystem root: /system /data /proc …
    if (parts.length <= 1) return true;

    const name = parts[parts.length - 1] || "";
    if (!FILES_PROTECTED_DIRS.has(name)) return false;
    // Well-known dirs at the first level under a storage root
    if (p.startsWith("/sdcard/") && parts.length === 2) return true;
    if (/^\/storage\/emulated\/\d+\//.test(p) && parts.length === 4) return true;
    if (/^\/storage\/[^/]+\//.test(p) && !p.startsWith("/storage/emulated/") && parts.length === 3) {
      return true;
    }
    if (/^\/mnt\/media_rw\/[^/]+\//.test(p) && parts.length === 4) return true;
    return false;
  }

  function filesCanDeleteCurrent() {
    return !filesIsProtectedPath(filesCurrentDir());
  }

  function filesRenderBreadcrumb() {
    const bc = $("filesBreadcrumb");
    if (!bc) return;
    const path = filesCurrentDir();
    const segments = path.split("/").filter(Boolean);
    let html = '<button type="button" class="files-bc-btn files-bc-root" data-path="/">/</button>';
    let acc = "";
    for (const seg of segments) {
      acc += "/" + seg;
      html += '<span class="files-bc-sep">\u203A</span>';
      html += '<button type="button" class="files-bc-btn" data-path="' +
        escapeHtml(acc) + '">' + escapeHtml(seg) + "</button>";
    }
    if (filesCanDeleteCurrent()) {
      html += '<button type="button" class="files-bc-del" id="filesBcDel" title="' +
        escapeHtml(t("filesDeleteFolder")) + '">' + FILES_ICON_DEL + "</button>";
    }
    bc.innerHTML = html;
    bc.querySelectorAll(".files-bc-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        const p = btn.getAttribute("data-path") || btn.dataset.path || "/";
        filesNavigate(p);
      });
    });
    const delBtn = $("filesBcDel");
    if (delBtn) {
      delBtn.addEventListener("click", () => {
        const cur = filesCurrentDir();
        const idx = cur.lastIndexOf("/");
        const parent = idx <= 0 ? "/" : cur.substring(0, idx);
        void filesDeleteEntry(cur, parent);
      });
    }
  }

  let _filesRenderBatch = 0;

  function filesFormatTime(mtime) {
    if (!mtime) return "";
    const d = new Date(mtime * 1000);
    if (isNaN(d.getTime())) return "";
    const mm = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    const hh = String(d.getHours()).padStart(2, "0");
    const mi = String(d.getMinutes()).padStart(2, "0");
    return d.getFullYear() + "-" + mm + "-" + dd + " " + hh + ":" + mi;
  }

  function filesRowHtml(e) {
    const name = escapeHtml(String(e.name || ""));
    const path = escapeHtml(String(e.path || ""));
    const isDir = !!e.dir;
    const size = isDir ? "" : filesFormatSize(e.size);
    const time = filesFormatTime(e.mtime);
    const icon = isDir ? FILES_ICON_FOLDER : FILES_ICON_FILE;
    const cls = isDir ? "files-row files-row-dir" : "files-row files-row-file";
    let acts = "";
    if (!isDir) {
      acts += '<button type="button" class="files-act-btn files-act-dl" data-path="' +
        path + '" data-name="' + name + '" title="' + escapeHtml(t("filesDownload")) +
        '">' + FILES_ICON_DL + "</button>";
      acts += '<button type="button" class="files-act-btn files-act-del" data-path="' +
        path + '" title="' + escapeHtml(t("filesDelete")) + '">' + FILES_ICON_DEL + "</button>";
    }
    return '<tr class="' + cls + '" data-path="' + path + '">' +
      '<td class="files-td-icon">' + icon + "</td>" +
      '<td class="files-td-name" title="' + name + '">' + name + "</td>" +
      '<td class="files-td-time">' + time + "</td>" +
      '<td class="files-td-size">' + size + "</td>" +
      '<td class="files-td-actions">' + acts + "</td></tr>";
  }

  function filesBindChunk(tbody) {
    tbody.querySelectorAll(".files-row-dir:not([data-bound])").forEach((tr) => {
      tr.dataset.bound = "1";
      const go = () => filesNavigate(tr.dataset.path);
      tr.querySelector(".files-td-name")?.addEventListener("click", go);
      tr.querySelector(".files-td-icon")?.addEventListener("click", go);
    });
    tbody.querySelectorAll(".files-act-dl:not([data-bound])").forEach((btn) => {
      btn.dataset.bound = "1";
      btn.addEventListener("click", (ev) => {
        ev.stopPropagation();
        filesTriggerDownload(filesDownloadUrl(btn.dataset.path), btn.dataset.name);
      });
    });
    tbody.querySelectorAll(".files-act-del:not([data-bound])").forEach((btn) => {
      btn.dataset.bound = "1";
      btn.addEventListener("click", (ev) => {
        ev.stopPropagation();
        filesDeleteEntry(btn.dataset.path);
      });
    });
  }

  function filesUpdateSortIndicators() {
    document.querySelectorAll(".files-th-sort").forEach((th) => {
      const key = th.dataset.sort;
      th.classList.toggle("files-th-active", key === state.filesSort);
      th.classList.toggle("files-th-asc", key === state.filesSort && state.filesSortAsc);
      th.classList.toggle("files-th-desc", key === state.filesSort && !state.filesSortAsc);
    });
  }

  function filesSortEntries(entries) {
    const key = state.filesSort || "name";
    const asc = state.filesSortAsc;
    entries.sort((a, b) => {
      if (a.dir && !b.dir) return -1;
      if (!a.dir && b.dir) return 1;
      let cmp = 0;
      if (key === "name") {
        cmp = String(a.name || "").localeCompare(String(b.name || ""));
      } else if (key === "time") {
        cmp = (a.mtime || 0) - (b.mtime || 0);
      } else if (key === "size") {
        cmp = (a.size || 0) - (b.size || 0);
      }
      return asc ? cmp : -cmp;
    });
  }

  function filesRenderList() {
    const tbody = $("filesTableBody");
    const empty = $("filesEmpty");
    if (!tbody) return;
    const entries = state.filesEntries || [];
    filesSortEntries(entries);
    filesUpdateSortIndicators();
    tbody.innerHTML = "";
    if (!entries.length) {
      if (empty) empty.classList.remove("hidden");
      return;
    }
    if (empty) empty.classList.add("hidden");

    const CHUNK = 40;
    const batchId = ++_filesRenderBatch;
    let offset = 0;

    function renderChunk() {
      if (batchId !== _filesRenderBatch) return;
      const end = Math.min(offset + CHUNK, entries.length);
      let html = "";
      for (let i = offset; i < end; i++) html += filesRowHtml(entries[i]);
      tbody.insertAdjacentHTML("beforeend", html);
      filesBindChunk(tbody);
      offset = end;
      if (offset < entries.length) {
        requestAnimationFrame(renderChunk);
      }
    }
    renderChunk();
  }

  async function filesFetchRoots(serial) {
    if (_filesAbort) { _filesAbort.abort(); _filesAbort = null; }
    const ac = new AbortController();
    _filesAbort = ac;
    const q = new URLSearchParams({ serial });
    const res = await fetch(`/v1/adb/files/roots?${q}`, { cache: "no-store", signal: ac.signal });
    const j = await res.json().catch(() => ({}));
    const roots = Array.isArray(j.roots) ? j.roots : [];
    return roots.map((r) => ({
      name: String(r.name || r.path || ""),
      path: String(r.path || ""),
      dir: true,
      size: 0,
    }));
  }

  async function filesNavigate(path, opts) {
    const serial = filesSerialValue();
    if (!serial) {
      const el = $("filesEmpty");
      if (el) { el.textContent = t("appsNoDevice"); el.classList.remove("hidden"); }
      return;
    }
    const force = !!(opts && opts.force);
    const target = (path == null || path === "") ? filesHomePath() : String(path);
    state.filesPath = target;
    filesRenderBreadcrumb();

    const navToken = ++_filesNavToken;
    const cached = force ? null : filesCacheGet(serial, target);
    const cacheFresh = cached && (Date.now() - cached.at) < FILES_CACHE_SOFT_MS;

    if (cached) {
      state.filesEntries = cached.entries;
      filesRenderList();
      filesShowBusy(false);
      // Never block the UI on a cache hit — only the refresh button uses force.
      if (cacheFresh) return;
      void (async () => {
        try {
          const entries = await filesLoadEntries(serial, target);
          if (navToken !== _filesNavToken) return;
          if (filesSerialValue() !== serial || state.filesPath !== target) return;
          state.filesEntries = entries;
          filesRenderList();
        } catch (e) {
          if (e?.name === "AbortError") return;
        }
      })();
      return;
    }

    filesShowBusy(true);
    try {
      const entries = await filesLoadEntries(serial, target);
      if (navToken !== _filesNavToken) return;
      state.filesEntries = entries;
      filesRenderList();
    } catch (e) {
      if (e?.name === "AbortError") return;
      if (navToken !== _filesNavToken) return;
      state.filesEntries = [];
      filesRenderList();
      showFleetToast(String(e?.message || "Error"), { err: true, key: "files" });
    } finally {
      if (navToken === _filesNavToken) filesShowBusy(false);
    }
  }

  async function filesDeleteEntry(path, navigateAfter) {
    if (!path) return;
    const name = path.split("/").pop() || path;
    if (filesIsProtectedPath(path)) {
      showFleetToast(t("filesDeleteProtected"), { err: true, key: "files-del" });
      return;
    }
    if (!window.confirm(t("filesDeleteConfirm").replace("{name}", name))) return;
    const serial = filesSerialValue();
    if (!serial) return;
    filesShowBusy(true);
    try {
      const res = await fetch("/v1/adb/files/delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ serial, path }),
      });
      const j = await res.json().catch(() => ({}));
      if (!res.ok || !j.ok) throw new Error(j.error || "delete_failed");
      showFleetToast(t("filesDeleteOk"), { key: "files-del" });
      filesCacheInvalidate(serial, path);
      await filesNavigate(navigateAfter || filesCurrentDir(), { force: true });
    } catch (_) {
      showFleetToast(t("filesDeleteFail"), { err: true, key: "files-del" });
      filesShowBusy(false);
    }
  }

  async function loadFilesView(force) {
    if (state.filesBusy) return;
    state.filesBusy = true;
    try {
      const needDevices = force || !filesSerialValue();
      if (needDevices) {
        await refreshAdbDeviceOptions();
      } else {
        // Keep select fresh, but never block tab-switch / cache paint on ADB.
        void refreshAdbDeviceOptions().catch(() => {});
      }
      await filesNavigate(state.filesPath || filesHomePath(), force ? { force: true } : undefined);
    } finally {
      state.filesBusy = false;
    }
  }

  function filesCurrentDir() {
    const p = String(state.filesPath || "/sdcard").trim();
    return p || "/sdcard";
  }

  async function filesUploadPicked(fileList) {
    const serial = filesSerialValue();
    if (!serial) {
      showFleetToast(t("appsNoDevice"), { err: true, key: "files" });
      return;
    }
    const files = [...(fileList || [])].filter(Boolean);
    if (!files.length) return;
    const dir = filesCurrentDir().replace(/\/$/, "");
    showFleetToast(t("filesUploading"), { key: "files-up" });
    let ok = 0;
    let fail = 0;
    for (const file of files) {
      const name = file.name || "upload.bin";
      const path = `${dir}/${name}`;
      try {
        const q = new URLSearchParams({ serial, path, name });
        const res = await fetch(`/v1/adb/files/upload?${q}`, {
          method: "POST",
          headers: { "Content-Type": "application/octet-stream" },
          body: file,
        });
        const j = await res.json().catch(() => ({}));
        if (res.ok && j.ok) ok += 1;
        else fail += 1;
      } catch (_) {
        fail += 1;
      }
    }
    if (fail) showFleetToast(t("filesUploadFail"), { err: true, key: "files-up" });
    else showFleetToast(t("filesUploadOk"), { key: "files-up" });
    if (ok) {
      filesCacheInvalidate(serial, dir || "/");
      void filesNavigate(filesCurrentDir(), { force: true });
    }
  }

  async function filesCreateFolder() {
    const serial = filesSerialValue();
    if (!serial) {
      showFleetToast(t("appsNoDevice"), { err: true, key: "files" });
      return;
    }
    const name = String(window.prompt(t("filesMkdirPrompt"), "") || "").trim();
    if (!name || /[\\/]/.test(name)) {
      if (name) showFleetToast(t("filesMkdirFail"), { err: true, key: "files-mkdir" });
      return;
    }
    const path = `${filesCurrentDir().replace(/\/$/, "")}/${name}`;
    try {
      const res = await fetch("/v1/adb/files/mkdir", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ serial, path }),
      });
      const j = await res.json().catch(() => ({}));
      if (!res.ok || !j.ok) throw new Error(j.error || "mkdir_failed");
      showFleetToast(t("filesMkdirOk"), { key: "files-mkdir" });
      filesCacheInvalidate(serial, filesCurrentDir());
      void filesNavigate(filesCurrentDir(), { force: true });
    } catch (_) {
      showFleetToast(t("filesMkdirFail"), { err: true, key: "files-mkdir" });
    }
  }

  function filesGoHome() {
    state.filesHome = "/sdcard";
    state.filesPath = "/sdcard";
    void filesNavigate("/sdcard");
  }

  /** @type {{ key: string, apps: any[], at: number } | null} */
  let _appsListCache = null;
  const APPS_CACHE_TTL_MS = 10 * 60_000;

  function appsCacheKey(serial, filter) {
    return `${String(serial || "")}\0${String(filter || "user")}`;
  }

  async function loadAppsView(force) {
    const wantForce = !!force;
    if (!wantForce && state.appsBusy) return;

    const needDevices = wantForce || !appsSerialValue();
    if (needDevices) {
      await refreshAdbDeviceOptions();
    } else {
      void refreshAdbDeviceOptions().catch(() => {});
    }

    const serial = appsSerialValue();
    const rows = $("appsRows");
    if (wantForce) state.appsPage = 1;
    if (!serial) {
      state.appsList = [];
      state.appsSelected = null;
      _appsListCache = null;
      if (rows) rows.innerHTML = `<div class="apps-empty">${escapeHtml(t("appsNoDevice"))}</div>`;
      renderAppsDetail(null);
      return;
    }

    const key = appsCacheKey(serial, state.appsFilter || "user");
    const cached =
      !wantForce &&
      _appsListCache &&
      _appsListCache.key === key &&
      Date.now() - _appsListCache.at < APPS_CACHE_TTL_MS
        ? _appsListCache
        : null;

    if (cached) {
      state.appsList = cached.apps.slice();
      renderAppsList();
      return;
    }

    // Already have an in-memory list for this serial/filter — paint it, then refresh quietly.
    if (
      !wantForce &&
      state.appsList.length &&
      appsCacheKey(state.appsSerial || serial, state.appsFilter || "user") === key
    ) {
      renderAppsList();
    }

    state.appsBusy = true;
    const hadList = Array.isArray(state.appsList) && state.appsList.length > 0;
    if (!hadList && rows) {
      rows.innerHTML = `<div class="apps-loading-wrap">${appsLoadingMarkup()}</div>`;
    }
    try {
      const q = new URLSearchParams({ serial, filter: state.appsFilter || "user" });
      const res = await fetch(`/v1/adb/apps?${q}`, { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      if (!j.ok && !Array.isArray(j.apps)) throw new Error(j.error || t("appsLoadFail"));
      state.appsList = Array.isArray(j.apps) ? j.apps.map(normalizeAppRecord).filter(Boolean) : [];
      state.appsSerial = serial;
      _appsListCache = { key, apps: state.appsList.slice(), at: Date.now() };
      renderAppsList();
      if (state.appsSelected) {
        const still = state.appsList.find((a) => a.package === state.appsSelected);
        if (still) await selectApp(still.package);
        else {
          state.appsSelected = null;
          renderAppsDetail(null);
        }
      }
    } catch (e) {
      if (!hadList && rows) {
        rows.innerHTML = `<div class="apps-empty">${escapeHtml(String(e.message || e || t("appsLoadFail")))}</div>`;
      }
      showFleetToast(t("appsLoadFail"), { err: true, key: "apps-load" });
    } finally {
      state.appsBusy = false;
    }
  }

  const appsIconCache = new Map();
  const appsIconFetching = new Set();
  const APPS_ICON_PLACEHOLDER = "/ava-icon-light.webp";
  const appsIconQueue = [];
  let appsIconInFlight = 0;
  const APPS_ICON_CONCURRENCY = 3;

  function pumpAppsIconQueue() {
    while (appsIconInFlight < APPS_ICON_CONCURRENCY && appsIconQueue.length) {
      const job = appsIconQueue.shift();
      if (!job) break;
      appsIconInFlight += 1;
      void job().finally(() => {
        appsIconInFlight -= 1;
        pumpAppsIconQueue();
      });
    }
  }

  function loadAppIcon(pkg, serial, imgEl) {
    if (!imgEl || !pkg) return;
    const cached = appsIconCache.get(pkg);
    if (cached) {
      imgEl.src = cached;
      return;
    }
    if (appsIconFetching.has(pkg)) return;
    appsIconFetching.add(pkg);
    appsIconQueue.push(async () => {
      try {
        const q = new URLSearchParams({ package: pkg });
        if (serial) q.set("serial", serial);
        const res = await fetch(`/v1/adb/apps/icon?${q}`, { cache: "force-cache" });
        if (res.status === 204 || !res.ok) throw new Error("icon_miss");
        const blob = await res.blob();
        if (!blob?.size) throw new Error("icon_empty");
        const blobUrl = URL.createObjectURL(blob);
        appsIconCache.set(pkg, blobUrl);
        if (imgEl.isConnected) imgEl.src = blobUrl;
      } catch (_) {
        appsIconCache.set(pkg, APPS_ICON_PLACEHOLDER);
        if (imgEl.isConnected) imgEl.src = APPS_ICON_PLACEHOLDER;
      } finally {
        appsIconFetching.delete(pkg);
      }
    });
    pumpAppsIconQueue();
  }

  function renderAppsList() {
    const rows = $("appsRows");
    if (!rows) return;
    const q = (state.appsQuery || ($("appsFilter")?.value || "")).trim().toLowerCase();
    let list = Array.isArray(state.appsList) ? state.appsList.slice() : [];
    if (q) list = list.filter((a) => String(a.package || "").toLowerCase().includes(q));
    if (!list.length) {
      rows.innerHTML = `<div class="apps-empty">${escapeHtml(t("appsEmpty"))}</div>`;
      updateAppsPagination(0, 1, 0);
      return;
    }
    const total = list.length;
    const pageCount = Math.max(1, Math.ceil(total / state.appsPageSize));
    state.appsPage = Math.min(Math.max(1, state.appsPage), pageCount);
    const pageStart = (state.appsPage - 1) * state.appsPageSize;
    updateAppsPagination(total, pageCount, pageStart);
    const serial = appsSerialValue();
    rows.innerHTML = list.slice(pageStart, pageStart + state.appsPageSize).map((a) => {
      const pkg = a.package || "";
      const typ = a.system ? t("appsTypeSystem") : t("appsTypeUser");
      const on = state.appsSelected === pkg ? " is-selected" : "";
      const iconSrc = appsIconCache.get(pkg) || APPS_ICON_PLACEHOLDER;
      const disabledBadge = a.disabled
        ? `<span class="apps-badge apps-badge-warn">${escapeHtml(t("appsStatusDisabled"))}</span>` : "";
      return `<div class="apps-item${on}" data-package="${escapeHtml(pkg)}">
        <img class="apps-icon" loading="lazy" decoding="async" src="${escapeHtml(iconSrc)}" alt="" />
        <div class="apps-info">
          <span class="apps-pkg" title="${escapeHtml(pkg)}">${escapeHtml(pkg)}</span>
        </div>
        <div class="apps-trail">
          ${disabledBadge}
          <span class="apps-badge ${a.system ? "apps-badge-sys" : "apps-badge-user"}">${escapeHtml(typ)}</span>
          <svg class="apps-chevron" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </div>
      </div>`;
    }).join("");
    rows.querySelectorAll(".apps-item").forEach((el) => {
      const pkg = el.getAttribute("data-package");
      const img = el.querySelector(".apps-icon");
      if (img && !appsIconCache.has(pkg)) void loadAppIcon(pkg, serial, img);
      el.addEventListener("click", () => void selectApp(el.getAttribute("data-package")));
    });
  }

  async function selectApp(pkg) {
    pkg = normalizePackageName(pkg);
    if (!pkg) return;
    state.appsSelected = pkg;
    renderAppsList();
    openAppsDetailModal();
    const title = $("appsDetailTitle");
    const meta = $("appsDetailMeta");
    const status = $("appsDetailStatus");
    if (title) {
      title.textContent = pkg;
      title.title = pkg;
    }
    if (status) status.textContent = "";
    if (meta) meta.innerHTML = appsLoadingMarkup();
    try {
      const q = new URLSearchParams({ package: pkg, serial: appsSerialValue() });
      const res = await fetch(`/v1/adb/apps/detail?${q}`, { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      renderAppsDetail(j);
      if (status) status.textContent = "";
    } catch (e) {
      if (meta) meta.innerHTML = `<div class="muted">${escapeHtml(String(e))}</div>`;
      if (status) status.textContent = String(e);
    }
  }

  function renderAppsDetail(j) {
    const meta = $("appsDetailMeta");
    const title = $("appsDetailTitle");
    const detailPackage = normalizePackageName(j?.package) || state.appsSelected;
    if (!j || !detailPackage) {
      closeAppsDetailModal({ clearSelection: false });
      if (meta) meta.innerHTML = `<div class="muted">${escapeHtml(t("appsSelectHint"))}</div>`;
      return;
    }
    openAppsDetailModal();
    const props = j.props || {};
    if (title) {
      title.textContent = detailPackage;
      title.title = detailPackage;
    }
    const rows = [
      [t("appsMetaVersionName"), props.versionName || "—"],
      [t("appsMetaVersionCode"), props.versionCode || "—"],
      [t("appsMetaFirstInstall"), props.firstInstallTime || "—"],
      [t("appsMetaLastUpdate"), props.lastUpdateTime || "—"],
    ];
    if (meta) {
      meta.innerHTML = rows.map(([k, v]) =>
        `<div class="apps-meta-item"><span>${escapeHtml(k)}</span><strong class="mono">${escapeHtml(String(v))}</strong></div>`,
      ).join("");
    }
  }

  function updateAppsPagination(total, pageCount, pageStart) {
    const summary = $("appsPageSummary");
    const label = $("appsPageLabel");
    const prev = $("appsPagePrev");
    const next = $("appsPageNext");
    if (summary) {
      summary.textContent = total
        ? t("appsPageSummary")
          .replace("{from}", String(pageStart + 1))
          .replace("{to}", String(Math.min(pageStart + state.appsPageSize, total)))
          .replace("{total}", String(total))
        : t("appsPageEmpty");
    }
    if (label) label.textContent = `${state.appsPage} / ${pageCount}`;
    if (prev) prev.disabled = state.appsPage <= 1;
    if (next) next.disabled = state.appsPage >= pageCount;
  }

  function openAppsDetailModal() {
    const modal = $("appsDetailPanel");
    if (!modal) return;
    modal.classList.remove("hidden");
    modal.setAttribute("aria-hidden", "false");
    document.body.classList.add("apps-modal-open");
    animateModalIn(modal);
    requestAnimationFrame(() => $("appsDetailCloseBtn")?.focus());
  }

  function closeAppsDetailModal({ clearSelection = true } = {}) {
    const modal = $("appsDetailPanel");
    if (!modal || modal.classList.contains("hidden")) return;
    if (clearSelection) state.appsSelected = null;
    const finish = () => {
      modal.classList.add("hidden");
      modal.setAttribute("aria-hidden", "true");
      document.body.classList.remove("apps-modal-open");
      motionClearInline(modal);
      motionClearInline(modalPanel(modal));
    };
    animateModalOut(modal).then(finish).catch(finish);
  }

  async function appsRunAction(action) {
    const pkg = state.appsSelected;
    if (!pkg) return;
    if (action === "clear" && !window.confirm(t("appsConfirmClear"))) return;
    if (action === "disable" && !window.confirm(t("appsConfirmDisable"))) return;
    const status = $("appsDetailStatus");
    if (status) status.textContent = "…";
    try {
      const res = await fetch("/v1/adb/apps/action", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ package: pkg, action, serial: appsSerialValue() }),
      });
      const j = await res.json().catch(() => ({}));
      const detail = [j.stdout, j.stderr, j.error].filter(Boolean).join("\n");
      if (j.ok) {
        showFleetToast(t("appsActionOk"), { key: "apps-act-ok" });
        if (status) status.textContent = detail || t("appsActionOk");
        if (action === "disable" || action === "enable" || action === "clear" || action === "uninstall") {
          await loadAppsView(true);
          await selectApp(pkg);
        }
      } else {
        showFleetToast(t("appsActionFail"), { err: true, key: "apps-act-fail" });
        if (status) status.textContent = detail || t("appsActionFail");
      }
    } catch (e) {
      showFleetToast(t("appsActionFail"), { err: true, key: "apps-act-fail" });
      if (status) status.textContent = String(e);
    }
  }

  function appsApkDownloadUrl(pkg, serial) {
    const q = new URLSearchParams({
      package: pkg || "",
      serial: serial || "",
    });
    const tok = fleetWireToken();
    if (tok) q.set("token", tok);
    return `/v1/adb/apps/apk?${q}`;
  }

  async function appsPullApk() {
    const pkg = state.appsSelected;
    if (!pkg) return;
    const status = $("appsDetailStatus");
    if (status) status.textContent = "…";
    const url = appsApkDownloadUrl(pkg, appsSerialValue());
    try {
      const res = await fetch(url, { cache: "no-store" });
      if (!res.ok) {
        const j = await res.json().catch(() => ({}));
        throw new Error(j.error || `HTTP ${res.status}`);
      }
      const blob = await res.blob();
      const obj = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = obj;
      a.download = `${pkg}.apk`;
      a.rel = "noopener";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(obj), 4000);
      showFleetToast(t("appsActionOk"), { key: "apps-pull-ok" });
      if (status) status.textContent = `${t("appsActionPull")}: ${blob.size} bytes`;
    } catch (e) {
      // Fallback: navigate with ?token= (no custom header).
      try {
        const a = document.createElement("a");
        a.href = url;
        a.download = `${pkg}.apk`;
        a.rel = "noopener";
        document.body.appendChild(a);
        a.click();
        a.remove();
        showFleetToast(t("appsActionOk"), { key: "apps-pull-ok" });
        if (status) status.textContent = t("appsActionPull");
      } catch (_) {
        showFleetToast(t("appsPullFail"), { err: true, key: "apps-pull-fail" });
        if (status) status.textContent = String(e);
      }
    }
  }

  async function appsInstallFile(file) {
    if (!file) return;
    const serial = appsSerialValue();
    if (!serial) {
      showFleetToast(t("appsNoDevice"), { err: true, key: "apps-nodev" });
      return;
    }
    showFleetToast(t("appsLoading"), { key: "apps-installing" });
    try {
      const buf = await file.arrayBuffer();
      const name = encodeURIComponent(file.name || "upload.apk");
      const res = await fetch(`/v1/adb/apps/install?serial=${encodeURIComponent(serial)}&name=${name}`, {
        method: "POST",
        headers: { "Content-Type": "application/vnd.android.package-archive" },
        body: buf,
      });
      const j = await res.json().catch(() => ({}));
      if (j.ok) {
        showFleetToast(t("appsInstallOk"), { key: "apps-inst-ok" });
        await loadAppsView(true);
      } else {
        showFleetToast(t("appsInstallFail"), { err: true, key: "apps-inst-fail" });
      }
    } catch (_) {
      showFleetToast(t("appsInstallFail"), { err: true, key: "apps-inst-fail" });
    }
  }

  function applyDeviceTab(tab) {
    const next = tab === "screen" || tab === "terminal" ? tab : "params";
    const switched = state.deviceTab !== next;
    if (switched && next === "params") state.peerParamsNeedFull = true;
    state.deviceTab = next;
    const params = $("deviceTabParams");
    const screen = $("deviceTabScreen");
    const terminal = $("deviceTabTerminal");
    if (params) params.classList.toggle("hidden", state.deviceTab !== "params");
    if (screen) screen.classList.toggle("hidden", state.deviceTab !== "screen");
    if (terminal) terminal.classList.toggle("hidden", state.deviceTab !== "terminal");
    document.querySelectorAll("[data-device-tab]").forEach((btn) => {
      btn.classList.toggle("on", btn.dataset.deviceTab === state.deviceTab);
    });
    if (state.deviceTab !== "screen" && state.streaming) stopScreen();
    if (state.deviceTab === "screen") {
      updateScreenAvailability(state.status || {});
      // Bring status-machine pill onto the stage when entering this tab.
      const keep = (state.screenMetaText || $("screenMeta")?.textContent || "").trim();
      if (keep) setScreenMeta(keep);
      else syncScreenStageStatus();
      const d = findDevice(state.focusDeviceId);
      if (d && !d.local && !hasLiveAdb(d) && !hasAdvertisedAgent(d)) {
        showScreenMessage(t("screenNeedAdb"), false);
      }
    }
    if (state.deviceTab === "terminal") {
      updateShellAvailability(state.status || {});
      ensureShellTerminal();
    }
    if (state.deviceTab === "params") {
      const d = findDevice(state.focusDeviceId);
      if (d && !d.local) void bootstrapPeerParams(d);
    }
    if (switched) {
      const panel = state.deviceTab === "screen" ? screen
        : state.deviceTab === "terminal" ? terminal
          : params;
      animatePanelEnter(panel);
    }
  }

  function renderDeviceFocus() {
    const d = findDevice(state.focusDeviceId);
    const s = state.status;
    if (!d || !s) return;
    $("deviceFocusName").textContent = d.name || d.id || "—";
    $("deviceFocusId").textContent = d.id || "";
    const localBadge = $("deviceLocalBadge");
    if (localBadge) {
      localBadge.hidden = !d.local;
      localBadge.title = t("localBadge");
      localBadge.setAttribute("aria-label", t("localBadge"));
    }
    if (d.local) {
      renderLocalDetail(s, d);
      const tel = s.telemetry || {};
      renderMetricKpis(tel);
      renderCharts(tel);
      renderModules(tel, "modGridFull");
      renderTelemetryDetail(tel);
      return;
    }

    const serial = resolveSerial(d);
    const cached = serial ? state.peerTelemetryBySerial[serial] : null;
    paintPeerIdentity(d, cached);
    $("modGridFull").innerHTML = `<div class="mod-empty muted">${escapeHtml(t("deviceRemoteModules"))}</div>`;
    $("modulesCount").textContent = "0";

    if (cached && cached.ok && !cached.lite) {
      paintPeerTelemetry(cached);
    } else if (serial && hasAdbAccess(d)) {
      // Bootstrap will full-pull once; show loading until it lands.
      $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbTelemetryLoading"))}</div>`;
      $("telemetryDetail").innerHTML = "";
      $("thermalRows").innerHTML = `<tr><td colspan="3" class="muted">—</td></tr>`;
    } else {
      const hostPort = normalizeAdbConnectHost(d.host);
      const connecting = !!(hostPort && state.adbAutoConnecting === hostPort);
      $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(
        connecting ? t("deviceAdbAutoConnecting") : t("deviceRemoteMetrics"),
      )}</div>`;
      $("telemetryDetail").innerHTML = "";
      $("thermalRows").innerHTML = `<tr><td colspan="3" class="muted">—</td></tr>`;
    }
  }

  function paintPeerIdentity(d, tel) {
    const idn = tel?.identity || {};
    $("localDetail").innerHTML = [
      ["__tags__", deviceTagsHtml(d), "html"],
      [t("fieldName"), d.name || "—", false],
      [t("fieldId"), d.id || "—", true],
      [t("fieldIp"), d.host || "—", true],
      [t("fieldAdbSerial"), resolveSerial(d) || "—", true],
      [t("fieldManufacturer"), idn.manufacturer || "—", false],
      [t("fieldModel"), idn.model || d.model || "—", false],
      [t("fieldAndroid"), idn.androidVersion || "—", false],
      [t("fieldUptime"), tel?.uptimeMs != null ? fmtUptime(tel.uptimeMs) : "—", true],
      [t("fieldPort"), deviceAgentPort(d) > 0 ? String(deviceAgentPort(d)) : "—", true],
      [t("fieldAccess"), hasAdvertisedAgent(d) ? (d.accessUrl || "—") : "—", true],
      [t("fieldSource"), (Array.isArray(d.sources) ? d.sources.join("+") : d.source) || "—", true],
      [t("colRecent"), formatSeen(d.lastSeenMs), false],
    ].map(([k, v, mode]) => {
      if (mode === "html") {
        return `<div class="detail-item detail-tags"><div class="k">${escapeHtml(t("tagLabel"))}</div><div class="v">${v}</div></div>`;
      }
      return `
        <div class="detail-item">
          <div class="k">${escapeHtml(k)}</div>
          <div class="v${mode ? " mono" : ""}">${escapeHtml(v)}</div>
        </div>`;
    }).join("");
  }

  function paintPeerTelemetry(tel) {
    renderMetricKpis(tel);
    renderCharts(tel);
    renderTelemetryDetail(tel);
  }

  async function loadPeerAdbTelemetry(serial, deep) {
    const key = String(serial || "").trim();
    if (!key) return null;
    const focus = findDevice(state.focusDeviceId);
    if (!focus || focus.local || resolveSerial(focus) !== key) return null;
    if (!deep && state.peerTelemetryInflight === key) return state.peerTelemetryBySerial[key] || null;
    state.peerTelemetryInflight = key;
    try {
      const q = `serial=${encodeURIComponent(key)}${deep ? "&deep=1" : ""}&_=${Date.now()}`;
      const res = await fetch(`/v1/adb/telemetry?${q}`, { cache: "no-store" });
      const tel = await res.json().catch(() => ({}));
      if (!res.ok || tel.ok === false) {
        if (state.view === "device" && resolveSerial(findDevice(state.focusDeviceId)) === key) {
          $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbTelemetryFail"))}</div>`;
        }
        return null;
      }
      state.peerTelemetryBySerial[key] = tel;
      state.peerParamsNeedFull = false;
      if (state.view === "device" && resolveSerial(findDevice(state.focusDeviceId)) === key) {
        paintPeerIdentity(findDevice(state.focusDeviceId), tel);
        paintPeerTelemetry(tel);
      }
      return tel;
    } catch (err) {
      console.warn("adb telemetry:", err);
      if (state.view === "device" && resolveSerial(findDevice(state.focusDeviceId)) === key) {
        $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbTelemetryFail"))}</div>`;
      }
      return null;
    } finally {
      if (state.peerTelemetryInflight === key) state.peerTelemetryInflight = "";
    }
  }

  function badgeOnline(online) {
    return online
      ? `<span class="badge ok"><span class="dot ok"></span>${escapeHtml(t("online"))}</span>`
      : `<span class="badge neutral"><span class="dot off"></span>${escapeHtml(t("offline"))}</span>`;
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function sourceLabel(source) {
    if (source === "hot") return t("sourceHot");
    if (source === "preview") return t("sourcePreview");
    return t("sourceBundled");
  }

  function fmtBytes(n) {
    if (n == null || Number.isNaN(n)) return "—";
    const u = ["B", "KB", "MB", "GB", "TB"];
    let v = Number(n);
    let i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i += 1; }
    return `${v.toFixed(i === 0 ? 0 : 1)} ${u[i]}`;
  }

  function fmtUptime(ms) {
    if (ms == null || Number.isNaN(Number(ms))) return "—";
    const sec = Math.floor(Number(ms) / 1000);
    const d = Math.floor(sec / 86400);
    const h = Math.floor((sec % 86400) / 3600);
    const m = Math.floor((sec % 3600) / 60);
    if (d > 0) return `${d}d ${h}h`;
    if (h > 0) return `${h}h ${m}m`;
    return `${m}m`;
  }

  function renderLocalDetail(s, d) {
    const screen = s.screen || {};
    const discovery = s.discovery || {};
    const shot = screen.oneshot || {};
    const caps = Array.isArray(s.capabilities) ? s.capabilities : [];
    const fields = [
      [t("fieldName"), d?.name || s.deviceName || "—", false],
      [t("fieldId"), d?.id || s.deviceId || "—", true],
      [t("fieldType"), d?.type || s.model || "—", false],
      [t("fieldManufacturer"), s.manufacturer || "—", false],
      [t("fieldModel"), s.model || "—", false],
      [t("fieldAndroid"), s.androidVersion || "—", false],
      [t("fieldVersion"), s.appVersion ? `v${s.appVersion}` : "—", false],
      [t("fieldProtocol"), `v${s.protocolVersion || 1}`, true],
      [t("fieldAccess"), s.accessUrl || "—", true],
      [t("fieldIp"), s.ip || t("noWifi"), true],
      [t("fieldPort"), String(s.port || 8888), true],
      [
        t("fieldDiscovery"),
        discovery.protocol
          ? `${discovery.protocol}:${discovery.port || 19848}`
          : "ava-voice-udp:19848",
        true,
      ],
      [
        t("fieldDiscoveryRun"),
        discovery.running ? t("running") : t("stopped"),
        false,
      ],
      [t("fieldVoicePort"), s.voicePort != null ? String(s.voicePort) : "—", true],
      [
        t("fieldVoice"),
        ({ running: t("running"), stopped: t("stopped"), disconnected: t("svcDisconnected"), error: t("svcError") })[s.voiceSatellite] || t("unknown"),
        false,
      ],
      [t("fieldUptime"), fmtUptime(s.uptimeMs), false],
      [
        t("fieldDisplay"),
        screen.displayWidth && screen.displayHeight
          ? `${screen.displayWidth}×${screen.displayHeight}`
          : "—",
        true,
      ],
      [t("fieldScreenMode"), screen.mode || "—", true],
      [
        t("fieldScreen"),
        screen.available ? t("screenAvailable") : t("screenUnavailable"),
        false,
      ],
      [
        t("fieldOneshot"),
        shot.hasShot
          ? `${t("screenOneshotCached")}${shot.width && shot.height ? ` · ${shot.width}×${shot.height}` : ""}`
          : (shot.canCapture ? t("screenModeOneshot") : t("screenScrcpyNeedShell")),
        false,
      ],
      [
        t("fieldTap"),
        screen.tapViaAccessibility ? t("tapReady") : t("tapNeedA11y"),
        false,
      ],
      [
        t("fieldCaps"),
        caps.length ? caps.join(", ") : "—",
        true,
      ],
      [t("fieldConsoleSource"), sourceLabel(s.consoleSource), false],
    ];
    $("localDetail").innerHTML = fields.map(([k, v, mono]) => `
      <div class="detail-item">
        <div class="k">${escapeHtml(k)}</div>
        <div class="v${mono ? " mono" : ""}">${escapeHtml(v)}</div>
      </div>
    `).join("");
  }

  function drawSeries(canvas, series, color) {
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth || 640;
    const h = canvas.clientHeight || 160;
    canvas.width = Math.floor(w * dpr);
    canvas.height = Math.floor(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = "rgba(148,163,184,0.18)";
    ctx.lineWidth = 1;
    for (let i = 1; i < 4; i += 1) {
      const y = (h / 4) * i;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }
    const pts = Array.isArray(series) ? series : [];
    if (pts.length < 2) {
      ctx.fillStyle = "rgba(148,163,184,0.7)";
      ctx.font = "12px sans-serif";
      ctx.fillText("…", 12, h / 2);
      return;
    }
    const values = pts.map((p) => Number(p.v));
    const max = Math.max(100, ...values);
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    values.forEach((v, i) => {
      const x = (i / (values.length - 1)) * (w - 8) + 4;
      const y = h - 6 - (v / max) * (h - 14);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
    const last = values[values.length - 1];
    ctx.fillStyle = color;
    ctx.font = "600 12px sans-serif";
    ctx.fillText(`${last.toFixed(1)}%`, w - 56, 16);
  }

  function renderMetricKpis(tel) {
    const cpu = tel?.cpu?.percent;
    const mem = tel?.memory?.usedPercent;
    const batt = tel?.battery?.levelPercent;
    const temp = tel?.battery?.temperatureC;
    const wifi = tel?.wifi?.rssiDbm;
    const thermal = tel?.thermal?.statusLabel;
    const storage = tel?.storage?.freeGb;
    const load = tel?.cpu?.loadavg?.m1;
    const items = [
      [t("metricCpu"), cpu == null ? "—" : `${Number(cpu).toFixed(1)}%`],
      [t("metricMem"), mem == null ? "—" : `${Number(mem).toFixed(1)}%`],
      [t("metricBatt"), batt == null ? "—" : `${batt}%`],
      [t("metricTemp"), temp == null ? "—" : `${Number(temp).toFixed(1)}°C`],
      [t("metricWifi"), wifi == null ? "—" : `${wifi} dBm`],
      [t("metricThermal"), thermal || "—"],
      [t("metricStorage"), storage == null ? "—" : `${storage} GB`],
      [t("metricLoad"), load == null ? "—" : String(load)],
    ];
    $("metricKpis").innerHTML = items.map(([k, v]) => `
      <div class="metric-kpi">
        <div class="label">${escapeHtml(k)}</div>
        <div class="value">${escapeHtml(v)}</div>
      </div>
    `).join("");
  }

  function renderCharts(tel) {
    const hist = tel?.history || {};
    const accent = getComputedStyle(document.body).getPropertyValue("--accent").trim() || "#a78b73";
    drawSeries($("cpuChart"), hist.cpuPercent, accent);
    drawSeries($("memChart"), hist.memoryUsedPercent, "#22c55e");
  }

  function renderModules(tel, targetId) {
    const mods = Array.isArray(tel?.modules) ? tel.modules : [];
    const el = $(targetId);
    if (!el) return;
    const countEl = $("modulesCount");
    if (countEl) countEl.textContent = String(mods.length);
    if (!mods.length) {
      el.innerHTML = `<div class="mod-empty muted">${escapeHtml(t("noModules"))}</div>`;
      return;
    }
    el.innerHTML = mods.map((m) => {
      const enabled = !!m.enabled;
      const badges = [
        enabled
          ? `<span class="badge ok">${escapeHtml(t("modEnabled"))}</span>`
          : `<span class="badge warn">${escapeHtml(t("modDisabled"))}</span>`,
        m.hasUpdate ? `<span class="badge info">${escapeHtml(t("modUpdate"))}</span>` : "",
        m.missingPermissions > 0
          ? `<span class="badge danger">${escapeHtml(t("modMissingPerm"))} ${m.missingPermissions}</span>`
          : "",
      ].filter(Boolean).join("");
      const ver = String(m.version || "").trim();
      const desc = String(m.description || "").trim();
      const id = String(m.id || "").trim();
      return `
        <article class="mod-card${enabled ? " is-on" : " is-off"}">
          <header class="mod-card-head">
            <div class="mod-card-title">
              <span class="mod-status" aria-hidden="true"></span>
              <div class="mod-card-copy">
                <div class="mod-name">${escapeHtml(m.name || id || "—")}</div>
                ${ver ? `<div class="mod-ver mono">${escapeHtml(ver)}</div>` : ""}
              </div>
            </div>
            <div class="mod-badges">${badges}</div>
          </header>
          ${desc && desc !== id ? `<p class="mod-desc">${escapeHtml(desc)}</p>` : ""}
          ${id ? `<footer class="mod-card-foot"><span class="mod-id mono">${escapeHtml(id)}</span></footer>` : ""}
        </article>`;
    }).join("");
  }

  function renderTelemetryDetail(tel) {
    const unk = t("unknown");
    const fields = [
      [t("telCpuPct"), tel?.cpu?.percent != null ? `${tel.cpu.percent}%` : "—", true],
      [t("telLoadavg"), tel?.cpu?.loadavg
        ? `${tel.cpu.loadavg.m1} / ${tel.cpu.loadavg.m5} / ${tel.cpu.loadavg.m15}`
        : "—", true],
      [t("telMemoryUsed"), tel?.memory?.usedPercent != null
        ? `${tel.memory.usedPercent}% (${fmtBytes(tel.memory.usedBytes)} / ${fmtBytes(tel.memory.totalBytes)})`
        : "—", false],
      [t("telStorageFree"), tel?.storage?.freeGb != null
        ? `${tel.storage.freeGb} GB (${tel.storage.usedPercent}% used)`
        : "—", false],
      [t("telBattery"), tel?.battery?.levelPercent != null
        ? `${tel.battery.levelPercent}% · ${tel.battery.chargeSource || unk} · ${tel.battery.voltageV ?? "—"}V`
        : "—", false],
      [t("telBatteryTemp"), tel?.battery?.temperatureC != null ? `${tel.battery.temperatureC}°C` : "—", true],
      [t("telThermal"), tel?.thermal?.statusLabel || "—", false],
      [t("telWifiRssi"), tel?.wifi?.rssiDbm != null ? `${tel.wifi.rssiDbm} dBm` : "—", true],
      [t("telWifiLink"), tel?.wifi?.linkSpeedMbps != null ? `${tel.wifi.linkSpeedMbps} Mbps` : "—", true],
      [t("telSsid"), tel?.wifi?.ssid || "—", false],
      [t("telAvaPss"), tel?.process?.pssKb != null ? `${tel.process.pssKb} KB` : "—", true],
      [t("telJavaHeap"), tel?.process
        ? `${fmtBytes(tel.process.javaHeapUsedBytes)} / ${fmtBytes(tel.process.javaHeapMaxBytes)}`
        : "—", true],
      [t("telLight"), tel?.sensors?.lightLux != null ? `${tel.sensors.lightLux} lux` : (tel?.sensors?.deferred ? "…" : "—"), true],
      [t("metricAmbient"), tel?.sensors?.ambientTemperatureC != null
        ? `${tel.sensors.ambientTemperatureC}°C`
        : "—", true],
    ];
    $("telemetryDetail").innerHTML = fields.map(([k, v, mono]) => `
      <div class="detail-item">
        <div class="k">${escapeHtml(k)}</div>
        <div class="v${mono ? " mono" : ""}">${escapeHtml(v)}</div>
      </div>
    `).join("");

    const zones = Array.isArray(tel?.thermal?.zones) ? tel.thermal.zones : [];
    if (!zones.length) {
      $("thermalRows").innerHTML = `<tr><td colspan="3" class="muted">—</td></tr>`;
    } else {
      $("thermalRows").innerHTML = zones.map((z) => `
        <tr>
          <td class="mono">${escapeHtml(z.name || "")}</td>
          <td>${escapeHtml(z.type || "")}</td>
          <td class="mono">${escapeHtml(String(z.celsius ?? ""))}</td>
        </tr>
      `).join("");
    }
  }

  function formatSeen(ms) {
    if (!ms) return "—";
    const age = Date.now() - ms;
    if (age < 8_000) return t("justNow");
    if (age < 60_000) return `${Math.round(age / 1000)}s`;
    return `${Math.round(age / 60_000)}m`;
  }

  function deviceHealthFlags(d, s) {
    const flags = [];
    // Control path is wireless ADB; Ava HTTP (clusterPort) is settings-sync only.
    if (!d.local && !hasAdbAccess(d)) flags.push(t("healthNoTransport"));
    if (!d.local && hasAdbAccess(d)) {
      const st = d.adbState || d.state;
      if (st && st !== "device") flags.push(String(st));
    }
    if (d.local) {
      if (s.voiceSatellite === "stopped" || s.voiceSatellite === "disconnected" || s.voiceSatellite === "error") flags.push(t("healthVoiceDown"));
      const sc = s.screen?.scrcpy || {};
      if (!sc.canLaunch && !sc.running && !s.screen?.oneshot?.canCapture) flags.push(t("healthNoShell"));
      const batt = s.telemetry?.battery?.levelPercent;
      if (batt != null && batt < 15) flags.push(t("healthLowBatt"));
      const cpu = s.telemetry?.cpu?.percent;
      if (cpu != null && cpu >= 90) flags.push(t("healthHighCpu"));
    }
    return flags;
  }

  /** Match wireless ADB session for a host (live adbFleet or device.serial). */
  function adbSerialForHost(host) {
    const h = String(host || "").trim();
    if (!h || h === "0.0.0.0") return "";
    for (const d of state.adbFleet || []) {
      if (!isAdbNetworkPeer(d)) continue;
      if (String(d.host || "").trim() === h && d.serial) return String(d.serial);
    }
    return "";
  }

  /** Wireless ADB serial for ops — never invent peer HTTP. */
  function resolveSerial(d) {
    if (!d || d.local) return "";
    const fromDevice = String(d.serial || "").trim();
    if (fromDevice && isAdbNetworkPeer({ ...d, serial: fromDevice })) return fromDevice;
    if (isAdbFleetDevice(d)) {
      const s = String(d.serial || String(d.id || "").replace(/^adb:/, "")).trim();
      if (s && isAdbNetworkPeer({ ...d, serial: s })) return s;
    }
    return adbSerialForHost(d.host);
  }

  function hasAdbAccess(d) {
    return !!resolveSerial(d);
  }

  function isAdbPresentState(st) {
    const s = String(st || "").trim().toLowerCase();
    return !s || s === "device" || s === "unauthorized";
  }

  /** Live wireless ADB transport (not leftover offline). */
  function hasLiveAdb(d) {
    return !!resolveSerial(d) && isAdbPresentState(d?.adbState || d?.state);
  }

  function canPeerScreen(d) {
    return !!(d && (d.local || hasLiveAdb(d) || hasAdvertisedAgent(d)));
  }

  /**
   * Params tab enter: soft ADB connect → warm screenshot → mandatory full telemetry once.
   * Same visit / re-render reuses cache; leaving+re-entering or「刷新传感器」pulls again.
   */
  async function bootstrapPeerParams(d) {
    if (!d || d.local) return;
    if (state.view !== "device" || state.deviceTab !== "params") return;
    if (state.focusDeviceId !== d.id) return;

    let serial = resolveSerial(d);
    if (!serial) {
      serial = await ensurePeerAdb(d);
    }
    if (!serial) return;
    if (state.view !== "device" || state.deviceTab !== "params" || state.focusDeviceId !== d.id) {
      return;
    }

    // Warm screenshot cache in parallel; never skip the first full metrics pull.
    void fetchPeerAdbShotOnce(serial);

    const prev = state.peerTelemetryBySerial[serial];
    const hasFull = !!(prev?.ok && !prev.lite);
    if (!state.peerParamsNeedFull && hasFull) {
      paintPeerIdentity(findDevice(d.id) || d, prev);
      paintPeerTelemetry(prev);
      return;
    }
    if (state.peerTelemetryInflight === serial) return;

    $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbTelemetryLoading"))}</div>`;
    await loadPeerAdbTelemetry(serial, true);
  }

  /** Cheap oneshot screencap — throttled; preferred first peer payload. */
  async function fetchPeerAdbShotOnce(serial) {
    const key = String(serial || "").trim();
    if (!key) return;
    const now = Date.now();
    const last = Number(state.adbShotAt[key] || 0);
    if (last && now - last < 20_000) return;
    state.adbShotAt[key] = now;
    try {
      const force = !last;
      await fetch(
        `/v1/adb/screen?serial=${encodeURIComponent(key)}&maxWidth=${WALL_SHOT_MAX_W}&quality=${WALL_SHOT_QUALITY}${force ? "&force=1" : ""}`,
        { cache: "no-store" },
      );
    } catch (_) {}
  }

  /**
   * UDP discovery already gave us a LAN IP — try wireless ADB (default :5555).
   * Only from params bootstrap / explicit sensor refresh — heavily throttled.
   */
  async function ensurePeerAdb(d, opts = {}) {
    if (!d || d.local || hasAdbAccess(d)) return resolveSerial(d) || null;
    if (!opts.force && (state.view !== "device" || state.deviceTab !== "params")) {
      return null;
    }
    const host = String(d.host || "").trim();
    if (!host || host === "0.0.0.0" || host === "127.0.0.1" || host === "localhost") {
      return null;
    }
    const hostPort = normalizeAdbConnectHost(host);
    if (!hostPort) return null;

    const now = Date.now();
    if (state.adbAutoConnecting === hostPort) return null;
    const last = Number(state.adbAutoConnectAt[hostPort] || 0);
    // Throttle failed/repeat attempts (success clears the stamp).
    if (!opts.force && last && now - last < 90_000) return null;

    state.adbAutoConnecting = hostPort;
    state.adbAutoConnectAt[hostPort] = now;
    if (state.view === "device" && state.focusDeviceId === d.id && state.deviceTab === "params") {
      $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbAutoConnecting"))}</div>`;
      paintPeerIdentity(d, null);
    }

    try {
      await fetch("/v1/adb/start", { method: "POST" }).catch(() => {});
      const res = await fetch("/v1/adb/connect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ hostPort }),
      });
      const j = await res.json().catch(() => ({}));
      if (j.error === "self_connect" || /self_connect/i.test(String(j.error || ""))) {
        return null;
      }
      if (!j.ok) {
        if (state.view === "device" && state.focusDeviceId === d.id && state.deviceTab === "params") {
          $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbAutoFail"))}</div>`;
        }
        return null;
      }

      delete state.adbAutoConnectAt[hostPort];
      await refreshAdbFleetDevices(true);
      try {
        if (typeof refresh === "function") await refresh();
      } catch (_) {}

      const still = findDevice(d.id) || d;
      const serial = resolveSerial(still) || hostPort;
      if (state.view === "device" && state.focusDeviceId === d.id) {
        paintPeerIdentity(still, null);
        updateScreenAvailability(state.status || {});
        if (state.deviceTab === "terminal") updateShellAvailability(state.status || {});
      }
      return serial;
    } catch (_) {
      if (state.view === "device" && state.focusDeviceId === d.id && state.deviceTab === "params") {
        $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbAutoFail"))}</div>`;
      }
      return null;
    } finally {
      if (state.adbAutoConnecting === hostPort) state.adbAutoConnecting = "";
    }
  }

  /** Advertised Ava HTTP agent only (local hub or beacon clusterPort > 0). */
  function hasAdvertisedAgent(d) {
    if (!d) return false;
    if (d.local) return true;
    return Number(d.clusterPort || 0) > 0;
  }

  /** Advertised Ava HTTP port only — never invent 8888. */
  function deviceAgentPort(d) {
    if (!d) return 0;
    const p = Number(d.clusterPort || 0);
    return p > 0 ? p : 0;
  }

  function wallShotEncode(opts = {}) {
    const wall = !!opts.wall;
    const first = !!(opts.force || opts.first);
    if (!wall) {
      return first ? { w: 720, q: 55 } : { w: 480, q: 35 };
    }
    if (first) return { w: WALL_SHOT_FIRST_MAX_W, q: WALL_SHOT_FIRST_QUALITY };
    return { w: WALL_SHOT_MAX_W, q: WALL_SHOT_QUALITY };
  }

  function deviceClusterScreenUrl(d, opts = {}) {
    if (!d || d.local || !hasAdvertisedAgent(d)) return "";
    const host = String(d.host || "").trim();
    const port = deviceAgentPort(d);
    if (!host || host === "0.0.0.0" || port <= 0) return "";
    const { w, q } = wallShotEncode({ wall: !!opts.wall, force: !!opts.force, first: !!opts.first });
    const force = !!opts.force;
    return `/v1/cluster/screen?host=${encodeURIComponent(host)}&port=${port}&oneshot=1&maxWidth=${w}&quality=${q}${force ? "&force=1" : ""}&_=${Date.now()}`;
  }

  function deviceFrameUrls(d, opts = {}) {
    if (!d) return [];
    const force = !!opts.force;
    const wall = !!opts.wall;
    const { w, q } = wallShotEncode(opts);
    if (d.local) {
      return [`/v1/screen/frame?quality=${q}&maxWidth=${w}${force ? "&force=1" : ""}&oneshot=1&_=${Date.now()}`];
    }
    const urls = [];
    const cluster = deviceClusterScreenUrl(d, opts);
    if (cluster) urls.push(cluster);
    // Prefer Ava oneshot. Live ADB is fallback only (never connect just for covers).
    const serial = resolveSerial(d);
    if (serial && hasLiveAdb(d)) {
      const bust = force ? `&_=${Date.now()}` : "";
      urls.push(`/v1/adb/screen?serial=${encodeURIComponent(serial)}&maxWidth=${w}&quality=${q}${force ? "&force=1" : ""}${bust}`);
    }
    return urls;
  }

  function deviceFrameUrl(d, opts = {}) {
    return deviceFrameUrls(d, opts)[0] || "";
  }

  function deviceTagSpecs(d) {
    if (!d) return [];
    if (d.local) {
      return [{ id: "local", tone: "local", label: t("tagLocal") }];
    }
    const tags = [];
    const sources = Array.isArray(d.sources) ? d.sources : [d.source].filter(Boolean);
    const hasAdb = hasAdbAccess(d) || sources.includes("adb") || isAdbFleetDevice(d);
    const hasAva = sources.includes("ava-voice-udp") || d.identity === "ava" || (!isAdbFleetDevice(d) && d.source !== "adb");
    if (hasAdb) tags.push({ id: "adb", tone: "adb", label: t("tagAdb") });
    if (hasAva) tags.push({ id: "ava", tone: "ava", label: t("tagAva") });
    if (!hasAdb && !hasAva) {
      tags.push({ id: "ava", tone: "ava", label: t("tagAva") });
    }
    if (hasAdvertisedAgent(d)) {
      tags.push({ id: "agent", tone: "agent", label: t("tagAgent") });
    }
    return tags;
  }

  function deviceTagsHtml(d) {
    const tags = deviceTagSpecs(d);
    if (!tags.length) return "";
    return `<span class="dev-tag-row">${tags.map((tag) =>
      `<span class="dev-tag dev-tag-${tag.tone}">${escapeHtml(tag.label)}</span>`,
    ).join("")}</span>`;
  }

  function wallHostText(d) {
    return d.host && d.host !== "0.0.0.0" ? String(d.host) : "";
  }

  function wallStatsText(d, s) {
    if (!d.local) return "";
    const tel = s?.telemetry || {};
    const extras = [];
    if (tel.battery?.levelPercent != null) extras.push(`${tel.battery.levelPercent}%`);
    if (tel.cpu?.percent != null) extras.push(`CPU ${tel.cpu.percent}%`);
    return extras.join(" · ");
  }

  function wallOverlayBodyHtml(d, s) {
    const host = wallHostText(d);
    const stats = wallStatsText(d, s);
    const bits = [host, stats].filter(Boolean);
    if (!bits.length) return "";
    return `<div class="dev-overlay-sub mono">${escapeHtml(bits.join(" · "))}</div>`;
  }

  /** Plain-text fallback (toasts / titles). */
  function faceStats(d, s) {
    const bits = [];
    const host = wallHostText(d);
    if (host) bits.push(host);
    bits.push(...deviceTagSpecs(d).map((tag) => tag.label));
    const stats = wallStatsText(d, s);
    if (stats) bits.push(stats);
    return bits.join(" · ");
  }

  function wallUptimeMs(d, s) {
    if (!d) return null;
    if (d.local) {
      const local = Number(s?.uptimeMs ?? s?.telemetry?.uptimeMs);
      if (Number.isFinite(local) && local >= 0) return local;
    }
    const serial = resolveSerial(d);
    if (serial) {
      const tel = state.peerTelemetryBySerial[serial];
      const fromTel = Number(tel?.uptimeMs);
      if (Number.isFinite(fromTel) && fromTel >= 0) return fromTel;
    }
    const direct = Number(d.uptimeMs);
    if (Number.isFinite(direct) && direct >= 0) return direct;
    return null;
  }

  function wallUptimeKey(d) {
    if (!d) return "";
    return resolveSerial(d) || String(d.id || "").trim();
  }

  function wallUptimePhase(d) {
    if (!d) return "na";
    if (d.local) return wallUptimeMs(d, state.status) != null ? "ok" : "fetching";
    if (wallUptimeMs(d, state.status) != null) return "ok";
    const key = wallUptimeKey(d);
    const phase = key ? state.adbUptimePhase[key] : "";
    if (phase === "ok" || phase === "fail" || phase === "fetching") return phase;
    if (hasLiveAdb(d)) return "fetching";
    return "na";
  }

  function wallUptimeLabel(d, s) {
    const base = wallUptimeMs(d, s);
    if (base != null) {
      let ms = base;
      let ts = NaN;
      if (d.local) {
        ts = Number(s?.ts);
      } else {
        const serial = resolveSerial(d);
        ts = Number(state.peerTelemetryBySerial[serial]?.ts);
      }
      if (Number.isFinite(ts) && ts > 0) {
        ms = Math.max(0, base + (Date.now() - ts));
      }
      return fmtUptime(ms);
    }
    const phase = wallUptimePhase(d);
    if (phase === "fetching") return t("wallUptimeFetching");
    return t("wallUptimeNa");
  }

  function paintWallUptimeEl(el, d, s) {
    if (!el) return;
    const phase = wallUptimePhase(d);
    el.textContent = wallUptimeLabel(d, s);
    el.classList.toggle("is-na", phase === "na" || phase === "fail");
    el.classList.toggle("is-fetching", phase === "fetching");
  }

  /**
   * Cheap ADB uptime for wall OSD — `cat /proc/uptime` only (no root).
   * Soft-throttled; merges into peerTelemetryBySerial without wiping full metrics.
   */
  async function fetchWallPeerUptime(d) {
    if (!d || d.local) return;
    const serial = resolveSerial(d);
    const key = wallUptimeKey(d);
    if (!serial) {
      if (key) state.adbUptimePhase[key] = "fail";
      return;
    }
    const now = Date.now();
    const prev = state.peerTelemetryBySerial[serial];
    if (prev?.uptimeMs != null && prev.ts && now - Number(prev.ts) < 55_000) {
      state.adbUptimePhase[key] = "ok";
      return;
    }
    const last = Number(state.adbUptimeAt[serial] || 0);
    if (last && now - last < 55_000) {
      // Recent attempt still cooling down — keep last phase (fetching/fail).
      if (!state.adbUptimePhase[key]) state.adbUptimePhase[key] = "fetching";
      return;
    }
    state.adbUptimeAt[serial] = now;
    state.adbUptimePhase[key] = "fetching";
    try {
      const res = await fetch(
        `/v1/adb/telemetry?serial=${encodeURIComponent(serial)}&uptime=1&_=${now}`,
        { cache: "no-store" },
      );
      const tel = await res.json().catch(() => ({}));
      if (!res.ok || tel.ok === false || tel.uptimeMs == null) {
        state.adbUptimePhase[key] = "fail";
        return;
      }
      if (prev?.ok && !prev.lite && !prev.uptimeOnly) {
        prev.uptimeMs = tel.uptimeMs;
        prev.ts = tel.ts || now;
        state.peerTelemetryBySerial[serial] = prev;
      } else {
        state.peerTelemetryBySerial[serial] = {
          ...(prev && typeof prev === "object" ? prev : {}),
          ok: true,
          lite: true,
          uptimeOnly: true,
          source: "adb",
          serial,
          uptimeMs: tel.uptimeMs,
          ts: tel.ts || now,
          identity: prev?.identity || {},
        };
      }
      state.adbUptimePhase[key] = "ok";
    } catch (_) {
      state.adbUptimePhase[key] = "fail";
    }
  }

  function wallKindLabel(d) {
    const tags = deviceTagSpecs(d);
    return tags[0]?.label || (d?.local ? t("tagLocal") : "—");
  }

  function wallKindTone(d) {
    const tags = deviceTagSpecs(d);
    return tags[0]?.tone || "ava";
  }

  function isWallMobile() {
    try {
      return window.matchMedia("(max-width: 700px)").matches;
    } catch (_) {
      return false;
    }
  }

  function wallGridDims(n) {
    const count = Math.max(1, Number(n) || 1);
    // Phones: at most 2 columns, square tiles scroll vertically.
    if (isWallMobile()) {
      const cols = Math.min(2, count);
      return [cols, Math.ceil(count / cols)];
    }
    if (count <= 1) return [1, 1];
    if (count <= 2) return [2, 1];
    if (count <= 4) return [2, 2];
    if (count <= 6) return [3, 2];
    if (count <= 9) return [3, 3];
    if (count <= 12) return [4, 3];
    if (count <= 16) return [4, 4];
    const cols = Math.ceil(Math.sqrt(count));
    return [cols, Math.ceil(count / cols)];
  }

  function applyWallGridLayout(el, cellCount) {
    if (!el) return [1, 1];
    const [cols, rows] = wallGridDims(cellCount);
    el.style.gridTemplateColumns = `repeat(${cols}, minmax(0, 1fr))`;
    if (isWallMobile()) {
      // Square cells sized by column width; rows grow with content.
      el.style.gridTemplateRows = `repeat(${rows}, auto)`;
      el.classList.add("wall-mobile");
    } else {
      el.style.gridTemplateRows = `repeat(${rows}, minmax(0, 1fr))`;
      el.classList.remove("wall-mobile");
    }
    el.dataset.wallCols = String(cols);
    el.dataset.wallRows = String(rows);
    return [cols, rows];
  }

  function syncOverviewWallChrome() {
    const isOverview = state.view === "overview";
    const listMode = isOverview && state.deviceViewMode === "list";
    document.body.classList.toggle("view-overview", isOverview);
    document.body.classList.toggle("wall-list-mode", listMode);
    const overview = $("view-overview");
    if (overview) overview.classList.toggle("wall-list-mode", listMode);
  }

  function wallStructureSig(list) {
    const mode = isWallMobile() ? "m2" : "d";
    // Omit volatile adb state — offline styling is live-updated. Including state
    // rebuilt the DOM mid-first-cover and left new tiles without a fetch.
    return `v6-osd-${mode}|${list.map((d) => [
      d.id,
      d.name,
      d.host,
      d.local ? 1 : 0,
      d.identity || "",
      Number(d.clusterPort || 0),
    ].join(":")).join("|")}`;
  }

  function startWallClock() {
    if (state.wallClockTimer) return;
    const tick = () => {
      const s = state.status;
      document.querySelectorAll("#fleetWall .dev-tile[data-open-device]").forEach((tile) => {
        const d = findDevice(tile.dataset.openDevice);
        const up = tile.querySelector(".osd-uptime");
        if (up) paintWallUptimeEl(up, d, s);
      });
    };
    tick();
    state.wallClockTimer = setInterval(tick, 1000);
  }

  function stopWallClock() {
    if (state.wallClockTimer) {
      clearInterval(state.wallClockTimer);
      state.wallClockTimer = null;
    }
  }

  function wallShotCacheKey(d) {
    if (!d) return "";
    return String(d.id || resolveSerial(d) || d.host || "").trim();
  }

  function openWallShotDb() {
    if (wallShotDbPromise) return wallShotDbPromise;
    wallShotDbPromise = new Promise((resolve, reject) => {
      if (!window.indexedDB) {
        reject(new Error("no_idb"));
        return;
      }
      const req = indexedDB.open(WALL_SHOT_DB, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(WALL_SHOT_STORE)) {
          db.createObjectStore(WALL_SHOT_STORE, { keyPath: "id" });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error("idb_open_failed"));
    }).catch((err) => {
      wallShotDbPromise = null;
      throw err;
    });
    return wallShotDbPromise;
  }

  function blobToDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ""));
      reader.onerror = () => reject(reader.error || new Error("read_failed"));
      reader.readAsDataURL(blob);
    });
  }

  async function wallShotCacheGet(id) {
    const key = String(id || "").trim();
    if (!key) return null;
    const mem = wallShotMem.get(key);
    if (mem?.dataUrl && Date.now() - mem.at < WALL_SHOT_MAX_AGE_MS) return mem.dataUrl;
    try {
      const db = await openWallShotDb();
      const row = await new Promise((resolve, reject) => {
        const tx = db.transaction(WALL_SHOT_STORE, "readonly");
        const req = tx.objectStore(WALL_SHOT_STORE).get(key);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => reject(req.error);
      });
      if (!row?.dataUrl) return null;
      if (Date.now() - Number(row.at || 0) > WALL_SHOT_MAX_AGE_MS) return null;
      wallShotMem.set(key, { dataUrl: row.dataUrl, at: Number(row.at) || Date.now() });
      return row.dataUrl;
    } catch (_) {
      return null;
    }
  }

  async function wallShotCachePut(id, blob) {
    const key = String(id || "").trim();
    if (!key || !blob?.size) return;
    try {
      const dataUrl = await blobToDataUrl(blob);
      if (!dataUrl.startsWith("data:image/")) return;
      const at = Date.now();
      wallShotMem.set(key, { dataUrl, at });
      const db = await openWallShotDb();
      await new Promise((resolve, reject) => {
        const tx = db.transaction(WALL_SHOT_STORE, "readwrite");
        tx.objectStore(WALL_SHOT_STORE).put({ id: key, dataUrl, at });
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
    } catch (_) {}
  }

  function applyWallShotToTile(tile, dataUrl) {
    if (!tile || !dataUrl) return false;
    const img = tile.querySelector(".dev-thumb");
    if (!img) return false;
    img.src = dataUrl;
    img.hidden = false;
    delete img.dataset.blobUrl;
    tile.classList.add("has-shot");
    tile.classList.remove("is-loading-shot");
    return true;
  }

  async function restoreWallThumbsFromCache(el) {
    const root = el || $("fleetWall");
    if (!root) return;
    const tiles = Array.from(root.querySelectorAll(".dev-tile[data-open-device]"));
    await Promise.all(tiles.map(async (tile) => {
      const d = findDevice(tile.dataset.openDevice);
      const key = wallShotCacheKey(d) || tile.dataset.openDevice;
      const cached = await wallShotCacheGet(key);
      if (cached) applyWallShotToTile(tile, cached);
    }));
  }

  async function fetchWallThumb(tile, d) {
    const img = tile.querySelector(".dev-thumb");
    if (!img) return;
    const cacheKey = wallShotCacheKey(d);
    // Prefer lasting frame: memory/IDB first so rebuilds never flash empty skeleton.
    if (!(tile.classList.contains("has-shot") && img.src && !img.hidden) && cacheKey) {
      const cached = await wallShotCacheGet(cacheKey);
      if (cached) applyWallShotToTile(tile, cached);
    }
    const hadShot = tile.classList.contains("has-shot") && !!img.src && !img.hidden;
    // First cover must capture (force); later passes can soft-hit peer/hub cache.
    const urls = deviceFrameUrls(d, { wall: true, force: !hadShot });
    if (!urls.length) {
      tile.classList.remove("is-loading-shot");
      return;
    }
    if (!hadShot) {
      tile.classList.add("is-loading-shot");
      tile.classList.remove("has-shot");
    } else {
      tile.classList.remove("is-loading-shot");
    }
    for (const url of urls) {
      if (!tile.isConnected) return;
      const ac = typeof AbortController !== "undefined" ? new AbortController() : null;
      const timer = ac
        ? setTimeout(() => { try { ac.abort(); } catch (_) {} }, WALL_THUMB_FETCH_MS)
        : null;
      try {
        const res = await fetch(url, { cache: "no-store", signal: ac ? ac.signal : undefined });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        if (!blob?.size) throw new Error("empty");
        const ct = `${res.headers.get("content-type") || ""} ${blob.type || ""}`.toLowerCase();
        // Some peers omit Content-Type; still try if payload looks usable.
        if (ct && !ct.includes("jpeg") && !ct.includes("octet-stream") && !ct.includes("image/")) {
          throw new Error("not_jpeg");
        }
        const obj = URL.createObjectURL(blob);
        const prev = img.dataset.blobUrl;
        const ok = await new Promise((resolve) => {
          img.onload = () => resolve(true);
          img.onerror = () => resolve(false);
          img.src = obj;
        });
        if (ok) {
          img.hidden = false;
          tile.classList.add("has-shot");
          tile.classList.remove("is-loading-shot");
          img.dataset.blobUrl = obj;
          delete tile.dataset.wallCoverRetry;
          if (prev && prev !== obj) {
            try { URL.revokeObjectURL(prev); } catch (_) {}
          }
          void wallShotCachePut(cacheKey, blob);
          return;
        }
        try { URL.revokeObjectURL(obj); } catch (_) {}
      } catch (_) {
        /* try next path (Ava agent, then live ADB) */
      } finally {
        if (timer) clearTimeout(timer);
      }
    }
    tile.classList.remove("is-loading-shot");
    if (!hadShot && !(tile.classList.contains("has-shot") && img.src)) {
      tile.classList.remove("has-shot");
      img.hidden = true;
      // One quick retry for cold first frame — do not wait a full 45s loop.
      if (tile.dataset.wallCoverRetry !== "1") {
        tile.dataset.wallCoverRetry = "1";
        setTimeout(() => {
          if (state.view !== "overview" || document.hidden || !tile.isConnected) return;
          if (tile.classList.contains("has-shot")) return;
          const again = findDevice(tile.dataset.openDevice);
          if (again) void fetchWallThumb(tile, again);
        }, 2500);
      }
    }
  }

  async function mapPool(items, limit, worker) {
    const list = Array.from(items || []);
    if (!list.length) return;
    const n = Math.max(1, Math.min(limit || 1, list.length));
    let i = 0;
    await Promise.all(Array.from({ length: n }, async () => {
      while (i < list.length) {
        const idx = i++;
        await worker(list[idx], idx);
      }
    }));
  }

  async function refreshWallThumbs() {
    if (state.view !== "overview" || document.hidden) return;
    if (state.wallThumbBusy) {
      state.wallThumbPending = true;
      return;
    }
    const el = $("fleetWall");
    if (!el) return;
    const tiles = Array.from(el.querySelectorAll(".dev-tile[data-open-device]"));
    if (!tiles.length) return;
    state.wallThumbBusy = true;
    state.wallThumbPending = false;
    try {
      await mapPool(tiles, WALL_THUMB_CONCURRENCY, async (tile) => {
        if (state.view !== "overview" || document.hidden) return;
        // Skip nodes torn down by a wall rebuild while this pass was queued.
        if (!tile.isConnected) return;
        const d = findDevice(tile.dataset.openDevice);
        if (!d) return;
        await fetchWallThumb(tile, d);
        if (state.view !== "overview" || document.hidden || !tile.isConnected) return;
        if (hasLiveAdb(d)) {
          await fetchWallPeerUptime(d);
          if (state.view !== "overview" || document.hidden || !tile.isConnected) return;
          const up = tile.querySelector(".osd-uptime");
          if (up) paintWallUptimeEl(up, d, state.status);
        }
      });
    } finally {
      state.wallThumbBusy = false;
      if (state.wallThumbPending && state.view === "overview" && !document.hidden) {
        state.wallThumbPending = false;
        void refreshWallThumbs();
      }
    }
  }

  function startWallThumbLoop(force = false) {
    if (state.view !== "overview" || document.hidden) return;
    if (state.wallThumbTimer && !force) return;
    stopWallThumbLoop();
    // DOM rebuild must not lose the first cover: queue if a pass is already in flight.
    void refreshWallThumbs();
    state.wallThumbTimer = setInterval(refreshWallThumbs, WALL_THUMB_MS);
  }

  function stopWallThumbLoop() {
    if (state.wallThumbTimer) {
      clearInterval(state.wallThumbTimer);
      state.wallThumbTimer = null;
    }
  }

  function updateFleetWallLive(el, list, s) {
    const tiles = Array.from(el.querySelectorAll("[data-open-device]"));
    list.forEach((d) => {
      const tile = tiles.find((n) => n.dataset.openDevice === d.id);
      if (!tile) return;
      const name = d.name || d.id || "—";
      const host = wallHostText(d) || "—";
      const flags = deviceHealthFlags(d, s);
      const adbSt = d.adbState || (hasAdbAccess(d) ? d.state : "");
      const offline = !!(hasAdbAccess(d) && adbSt && adbSt !== "device");
      const nameEl = tile.querySelector(".osd-name");
      if (nameEl) nameEl.textContent = name;
      const hostEl = tile.querySelector(".osd-host");
      if (hostEl) hostEl.textContent = host;
      const kindEl = tile.querySelector(".osd-kind");
      if (kindEl) {
        kindEl.textContent = wallKindLabel(d);
        kindEl.className = `osd-kind tone-${wallKindTone(d)}`;
      }
      const upEl = tile.querySelector(".osd-uptime");
      if (upEl) paintWallUptimeEl(upEl, d, s);
      tile.classList.toggle("is-warn", flags.length > 0 && !offline);
      tile.classList.toggle("is-off", !!offline);
      tile.classList.toggle("is-local", !!d.local);
      tile.title = flags.join(", ") || t("healthOk");
    });
  }

  function renderFleetWall(s) {
    const el = $("fleetWall");
    if (!el) return;
    const q = ($("deviceFilter")?.value || "").trim().toLowerCase();
    let list = fleetDevices(s);
    if (q) list = list.filter((d) => `${d.name || ""} ${d.host || ""} ${d.id || ""}`.toLowerCase().includes(q));

    const addTileHtml = `
      <button type="button" class="dev-tile dev-tile-add" id="fleetWallAddBtn" data-adb-add aria-label="${escapeHtml(t("fleetWallAddDevice"))}">
        <div class="dev-mod">
          <div class="dev-add-inner">
            <span class="dev-add-plus" aria-hidden="true">+</span>
            <span class="dev-add-label">${escapeHtml(t("fleetWallAddDevice"))}</span>
          </div>
        </div>
      </button>`;

    if (!list.length) {
      applyWallGridLayout(el, 1);
      el.innerHTML = addTileHtml;
      state.wallSig = "empty+add";
      animateWallEnter(el);
      stopWallThumbLoop();
      stopWallClock();
      return;
    }

    const sig = wallStructureSig(list);
    if (sig === state.wallSig && el.querySelector(".dev-tile[data-open-device]")) {
      updateFleetWallLive(el, list, s);
      if (state.view === "overview") {
        startWallThumbLoop(false);
        startWallClock();
      }
      return;
    }
    state.wallSig = sig;

    el.querySelectorAll(".dev-thumb[data-blob-url]").forEach((img) => {
      try { URL.revokeObjectURL(img.dataset.blobUrl); } catch (_) {}
    });

    const activeCells = list.length + 1; // + add channel
    const [cols, rows] = applyWallGridLayout(el, activeCells);
    const blanks = Math.max(0, cols * rows - activeCells);

    el.innerHTML = list.map((d) => {
      const flags = deviceHealthFlags(d, s);
      const adbSt = d.adbState || (hasAdbAccess(d) ? d.state : "");
      const offline = !!(hasAdbAccess(d) && adbSt && adbSt !== "device");
      const name = d.name || d.id || "—";
      const host = wallHostText(d) || "—";
      const kind = wallKindLabel(d);
      const tone = wallKindTone(d);
      const uptime = wallUptimeLabel(d, s);
      const upPhase = wallUptimePhase(d);
      const cacheKey = wallShotCacheKey(d);
      const memShot = cacheKey ? wallShotMem.get(cacheKey)?.dataUrl : "";
      const cls = [
        "dev-tile",
        memShot ? "has-shot" : "is-loading-shot",
        flags.length && !offline ? "is-warn" : "",
        offline ? "is-off" : "",
        d.local ? "is-local" : "",
      ].filter(Boolean).join(" ");
      const upCls = [
        "osd-uptime",
        upPhase === "fetching" ? "is-fetching" : "",
        upPhase === "na" || upPhase === "fail" ? "is-na" : "",
      ].filter(Boolean).join(" ");
      return `
        <button type="button" class="${cls}" data-open-device="${escapeHtml(d.id)}" aria-label="${escapeHtml(name)}" title="${escapeHtml(flags.join(", ") || t("healthOk"))}">
          <div class="dev-mod">
            <div class="dev-ph" aria-hidden="true"></div>
            <img class="dev-thumb" alt="" ${memShot ? `src="${memShot}"` : "hidden"} />
            <div class="osd top">
              <span class="osd-kind tone-${escapeHtml(tone)}">${escapeHtml(kind)}</span>
              <span class="osd-pip" aria-hidden="true"></span>
              <em class="osd-host">${escapeHtml(host)}</em>
            </div>
            <div class="osd bot">
              <strong class="osd-name">${escapeHtml(name)}</strong>
              <span class="${upCls}">${escapeHtml(uptime)}</span>
            </div>
          </div>
        </button>`;
    }).join("") + addTileHtml + Array.from({ length: blanks }, () =>
      `<div class="dev-tile-empty" aria-hidden="true"></div>`,
    ).join("");

    el.querySelectorAll("[data-open-device]").forEach((tile) => {
      tile.addEventListener("click", () => openDevice(tile.dataset.openDevice, "params"));
    });

    // IDB restore for cold start / tiles without memory hit (async; no skeleton flash if found).
    void restoreWallThumbsFromCache(el);

    animateWallEnter(el);

    if (state.view === "overview") {
      startWallThumbLoop(true);
      startWallClock();
    } else {
      stopWallThumbLoop();
      stopWallClock();
    }
  }


  /** Ava UDP + ADB targets shown on the wall / device list (clickable). */
  function isAdbFleetDevice(d) {
    if (!d) return false;
    if (d.source === "adb" || d.identity === "adb") return true;
    return String(d.id || "").startsWith("adb:");
  }

  function isAdbNetworkPeer(d) {
    const serial = String(d?.serial || String(d?.id || "").replace(/^adb:/, "")).trim();
    if (!serial) return false;
    // Local adb transport (never a fleet peer row).
    if (/^emulator-\d+$/i.test(serial)) return false;
    // Only wireless host:port with a dotted host (IPv4 / hostname.tld).
    if (!serial.includes(".") || !/:\d+$/.test(serial)) return false;
    if (isAdbSelfDevice({ serial, model: d?.model, product: d?.product })) return false;
    return true;
  }

  function adbToFleetDevice(d) {
    const serial = String(d?.serial || "").trim();
    if (!serial || !isAdbNetworkPeer({ ...d, serial })) return null;
    const host = serial.includes(":")
      ? serial.split(":").slice(0, -1).join(":")
      : serial;
    const model = d.model || d.product || "";
    return {
      id: `adb:${serial}`,
      name: model || serial,
      host,
      serial,
      type: "adb",
      identity: "adb",
      local: false,
      clusterEnabled: false,
      consoleEnabled: false,
      clusterPort: 0,
      // No Ava HTTP on the peer — screen/input go through this host's ADB.
      accessUrl: "",
      lastSeenMs: Date.now(),
      source: "adb",
      state: d.state || "device",
      lostAtMs: Number(d.lostAtMs || 0) || 0,
      model,
    };
  }

  /** Peer also serves the website SPA (optional; hub only). */
  function hasWebConsole(d) {
    if (!hasAdvertisedAgent(d)) return false;
    if (d.local) return d.consoleEnabled !== false;
    return d.consoleEnabled === true;
  }

  async function refreshAdbFleetDevices(force = false) {
    const now = Date.now();
    if (!force && state.adbFleetAt && now - state.adbFleetAt < 4000) return;
    try {
      const res = await fetch("/v1/adb/devices", { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      const raw = Array.isArray(j.devices) ? j.devices : [];
      const prevAll = new Set((state.adbFleet || []).map((d) => d.serial).filter(Boolean));
      // Keep leftover offline peers during the 2-minute grace; USB leftovers stay out.
      const mapped = raw
        .filter((d) => {
          const st = String(d.state || "").trim().toLowerCase();
          return !st || st === "device" || st === "unauthorized" || st === "offline";
        })
        .map(adbToFleetDevice)
        .filter(Boolean);
      const nextLive = new Set();
      const nextAll = new Set();
      for (const d of mapped) {
        nextAll.add(d.serial);
        if (isAdbPresentState(d.state)) {
          nextLive.add(d.serial);
          state.adbLostPrompted.delete(d.serial);
          state.adbLostDeferred.delete(d.serial);
        }
      }
      state.adbKnownLive = nextLive;
      state.adbFleet = mapped;
      state.adbFleetAt = now;
      const lostPeers = [];
      for (const d of mapped) {
        if (isAdbPresentState(d.state)) continue;
        if (state.adbLostPrompted.has(d.serial)) continue;
        state.adbLostPrompted.add(d.serial);
        lostPeers.push(d);
      }
      for (const serial of prevAll) {
        if (nextAll.has(serial)) continue;
        if (state.adbLostDeferred.has(serial)) {
          showFleetToast(t("adbLostAutoClean").replace("{name}", serial), { key: `adb-auto-${serial}` });
        }
        state.adbLostPrompted.delete(serial);
        state.adbLostDeferred.delete(serial);
      }
      if (lostPeers.length) {
        setTimeout(() => {
          for (const d of lostPeers) void notifyAdbLost(d);
        }, 800);
      }
    } catch (_) {
      /* keep last snapshot */
    }
  }

  async function notifyAdbLost(d) {
    const name = d.model || d.name || d.serial || "";
    showFleetToast(t("adbLostToast").replace("{name}", name), { key: `adb-lost-${d.serial}` });
    if (window.confirm(t("adbLostConfirm").replace("{name}", name))) {
      await adbForget(d.serial, { confirm: false });
    } else {
      state.adbLostDeferred.add(d.serial);
    }
  }

  /** Merge UDP directory + live ADB by host — one wall card, serial attached. */
  function fleetDevices(s) {
    const list = Array.isArray(s?.devices) ? s.devices.slice() : [];
    const cleaned = list.filter((d) => {
      if (!d) return false;
      if (isAdbFleetDevice(d) && !isAdbNetworkPeer(d)) return false;
      return true;
    }).map((d) => {
      const sources = Array.isArray(d.sources)
        ? d.sources.slice()
        : [d.source].filter(Boolean);
      return { ...d, sources, clusterPort: Number(d.clusterPort || 0) };
    });

    const byHost = new Map();
    const noHost = [];
    for (const d of cleaned) {
      const host = String(d.host || "").trim();
      if (host && host !== "0.0.0.0") {
        const key = host.toLowerCase();
        const prev = byHost.get(key);
        if (!prev) byHost.set(key, d);
        else byHost.set(key, mergeDeviceCards(prev, d));
      } else {
        noHost.push(d);
      }
    }

    for (const d of state.adbFleet || []) {
      if (!isAdbNetworkPeer(d)) continue;
      const host = String(d.host || "").trim();
      if (!host || host === "0.0.0.0") continue;
      const key = host.toLowerCase();
      const prev = byHost.get(key);
      if (prev) byHost.set(key, mergeDeviceCards(prev, d));
      else byHost.set(key, { ...d, sources: ["adb"] });
    }

    return [...byHost.values(), ...noHost];
  }

  function mergeDeviceCards(a, b) {
    const sources = [...new Set([
      ...(Array.isArray(a.sources) ? a.sources : [a.source].filter(Boolean)),
      ...(Array.isArray(b.sources) ? b.sources : [b.source].filter(Boolean)),
    ])];
    const preferAva = a.identity === "ava" || a.source === "ava-voice-udp" || a.source === "local";
    const base = preferAva ? a : (b.identity === "ava" || b.source === "ava-voice-udp" ? b : a);
    const other = base === a ? b : a;
    const portA = Number(a.clusterPort || 0);
    const portB = Number(b.clusterPort || 0);
    const clusterPort = Math.max(portA, portB);
    const serial = resolveSerial(base) || resolveSerial(other)
      || String(base.serial || other.serial || "").trim();
    return {
      ...other,
      ...base,
      serial: serial || base.serial || other.serial || "",
      clusterPort,
      clusterEnabled: clusterPort > 0,
      consoleEnabled: !!(base.consoleEnabled || other.consoleEnabled),
      accessUrl: clusterPort > 0 ? (base.accessUrl || other.accessUrl || "") : "",
      sources,
      state: other.state || base.state || "",
      adbState: other.adbState || base.adbState || other.state || "",
      lostAtMs: Math.max(Number(a.lostAtMs || 0), Number(b.lostAtMs || 0)),
      model: base.model || other.model || "",
      lastSeenMs: Math.max(Number(base.lastSeenMs || 0), Number(other.lastSeenMs || 0)),
    };
  }

  function renderDevices(s) {
    if (!$("deviceRows")) return;
    const q = ($("deviceFilter")?.value || "").trim().toLowerCase();
    const list = fleetDevices(s);
    const filtered = list.filter((d) => {
      if (!q) return true;
      const hay = `${d.name || ""} ${d.host || ""} ${d.id || ""}`.toLowerCase();
      return hay.includes(q);
    });
    if (!filtered.length) {
      const empty = q ? t("noMatch") : t("noDevices");
      $("deviceRows").innerHTML = `
        <tr>
          <td colspan="4">
            <div class="devices-empty">
              <span class="muted">${escapeHtml(empty)}</span>
              ${q ? "" : `<button type="button" class="btn primary" data-adb-add>${escapeHtml(t("fleetWallAddDevice"))}</button>`}
            </div>
          </td>
        </tr>`;
      return;
    }
    $("deviceRows").innerHTML = filtered.map((d) => {
      const canScreen = canPeerScreen(d);
      const tags = deviceTagsHtml(d);
      const badges = d.local ? localStarHtml() : "";
      const host = d.host && d.host !== "0.0.0.0" ? d.host : "—";
      const screenDisabled = canScreen ? "" : " disabled";
      const action = `
        <div class="row-actions">
          <button type="button" class="btn" data-device-action="params" data-id="${escapeHtml(d.id)}">${escapeHtml(t("deviceTabParams"))}</button>
          <button type="button" class="btn primary" data-device-action="screen" data-id="${escapeHtml(d.id)}"${screenDisabled}>${escapeHtml(t("deviceTabScreen"))}</button>
        </div>`;
      return `
        <tr>
          <td>
            <div class="name"><span>${escapeHtml(d.name || d.id || "—")}</span>${badges}</div>
          </td>
          <td>${badgeOnline(true)}</td>
          <td class="device-meta">
            <div class="device-meta-inner">
              <span class="mono device-host">${escapeHtml(host)}</span>
              ${tags}
            </div>
          </td>
          <td>${action}</td>
        </tr>
      `;
    }).join("");
    $("deviceRows").querySelectorAll("[data-device-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        if (btn.disabled) return;
        openDevice(btn.dataset.id, btn.dataset.deviceAction);
      });
    });
  }

  function render(s) {
    state.status = s;
    if (s && s.auth) state.authRequired = !!s.auth.required;
    syncLockSub();
    // Root rule: never paint the dashboard until the auth gate has started live mode.
    if (!state.live) return;
    paintDashboard(s);
  }

  function paintDashboard(s) {
    const devices = fleetDevices(s);
    $("liveBadge").classList.remove("err");
    setLiveState("live");
    renderFleetWall(s);
    renderDevices(s);
    if (state.view === "activity") void refreshAdbDeviceOptions();
    if (state.view === "device" && state.focusDeviceId) {
      renderDeviceFocus();
      updateScreenAvailability(s);
      if (state.deviceTab === "terminal") updateShellAvailability(s);
    }
    syncAuthBannerFromStatus(s);
  }

  function setLiveState(stateName) {
    const el = $("liveBadge");
    if (!el) return;
    const map = {
      connecting: t("connecting"),
      live: t("statusLive"),
      error: t("statusErrorShort"),
      idle: t("statusIdle"),
    };
    const key = map[stateName] ? stateName : "idle";
    el.dataset.state = key;
    el.classList.toggle("err", key === "error");
    const label = el.querySelector(".live-label");
    if (label) label.textContent = map[key] || map.idle;
    else {
      el.innerHTML = `<span class="pulse"></span><span class="live-label">${escapeHtml(map[key] || map.idle)}</span>`;
    }
  }

  function renderError(message) {
    setLiveState("error");
    const label = $("liveBadge")?.querySelector(".live-label");
    if (label && message) label.textContent = message;
    $("localDetail").innerHTML = `<div class="muted">${escapeHtml(message)}</div>`;
  }

  function updateScreenAvailability(s) {
    const d = findDevice(state.focusDeviceId);
    const peerAdb = !!(d && !d.local && hasLiveAdb(d));
    const peerCluster = !!(d && !d.local && hasAdvertisedAgent(d));
    const screen = s.screen || {};
    const sc = screen.scrcpy || {};
    const shot = screen.oneshot || {};
    const a11yOk = !!(screen.captureViaAccessibility || shot.canAccessibilityCapture);
    const shellOk = !!(screen.captureViaShell || shot.canShellCapture || sc.canLaunch || sc.running);
    const localOk = !!(screen.available || shot.canCapture || shellOk || a11yOk);
    const ok = peerAdb || peerCluster || !!(d && d.local && localOk);
    if (!ok && state.streaming && !state.adbScreen && !state.a11yScreen && !state.clusterScreen) stopScreen();
    const enableBtn = $("screenEnableA11yBtn");
    if (enableBtn) {
      // Only offer Settings jump when local, idle, and nothing can capture yet.
      const showEnable = !!(d && d.local && !localOk && !state.streaming);
      enableBtn.classList.toggle("hidden", !showEnable);
    }
    // Live session owns stage status pill; only fill capacity line when idle.
    if (state.streaming || state.scrcpyOwned || state.adbScreen || state.a11yScreen || state.clusterScreen || hasScreenFrame()) return;
    const bits = [];
    if (peerAdb) {
      bits.push(t("screenModeAdb"));
    } else if (peerCluster) {
      bits.push(t("screenModeCluster"));
    } else if (d && !d.local) {
      bits.push(t("screenNeedAdb"));
    } else if (!localOk) {
      bits.push(t("screenNeedA11yOrShell"));
    } else if (a11yOk && !shellOk) {
      bits.push(t("screenModeA11y"));
      bits.push(screen.tapViaAccessibility ? t("tapReady") : t("tapNeedA11y"));
    } else {
      bits.push(t("screenModeHybrid"));
      const backend = shot.backend || sc.shellBackend;
      if (backend) bits.push(backend);
      bits.push(screen.tapAvailable || screen.tapViaAccessibility || screen.tapViaShell
        ? t("tapReady")
        : t("tapNeedA11y"));
    }
    setScreenMeta(bits.join(" · "));
  }

  function updateShellAvailability(s) {
    const shell = s?.shell || {};
    const meta = $("shellMeta");
    const d = findDevice(state.focusDeviceId);
    if (meta) {
      if (d && !d.local) {
        meta.textContent = hasAdbAccess(d)
          ? `${t("shellReady")} · ADB · ${resolveSerial(d)}`
          : t("screenNeedAdb");
      } else if (shell.available || shell.backend) {
        meta.textContent = `${t("shellReady")} · ${shell.backend || "—"}`;
      } else {
        meta.textContent = t("shellNeedShizuku");
      }
    }
    if (state.deviceTab === "terminal") ensureShellTerminal();
  }

  function shellPromptText() {
    const cwd = state.termCwd || "/";
    const short = cwd.length > 28 ? "…" + cwd.slice(-27) : cwd;
    return `ava:${short} $ `;
  }

  function termWrite(text) {
    if (state.term) state.term.write(text);
  }

  function termWriteln(text) {
    termWrite(`${text || ""}\r\n`);
  }

  function ensureShellTerminal() {
    const host = $("shellXterm");
    if (!host) return;
    if (typeof Terminal === "undefined") {
      host.textContent = t("shellXtermMissing");
      return;
    }
    if (state.term) {
      fitShellTerminal();
      try { state.term.focus(); } catch (_) {}
      return;
    }

    const term = new Terminal({
      cursorBlink: true,
      cursorStyle: "block",
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
      fontSize: 13,
      lineHeight: 1.25,
      scrollback: 5000,
      theme: {
        background: "#0b0d10",
        foreground: "#d1d5db",
        cursor: "#7dd3fc",
        cursorAccent: "#0b0d10",
        selectionBackground: "rgba(125, 211, 252, 0.28)",
        black: "#0b0d10",
        red: "#fca5a5",
        green: "#86efac",
        yellow: "#fde68a",
        blue: "#7dd3fc",
        magenta: "#d8b4fe",
        cyan: "#67e8f9",
        white: "#e5e7eb",
        brightBlack: "#6b7280",
        brightRed: "#fecaca",
        brightGreen: "#bbf7d0",
        brightYellow: "#fef08a",
        brightBlue: "#bae6fd",
        brightMagenta: "#e9d5ff",
        brightCyan: "#a5f3fc",
        brightWhite: "#ffffff",
      },
      allowProposedApi: true,
    });

    let fitAddon = null;
    try {
      const FitNS = window.FitAddon;
      const FitCtor = FitNS && (FitNS.FitAddon || FitNS);
      if (typeof FitCtor === "function") {
        fitAddon = new FitCtor();
        term.loadAddon(fitAddon);
      }
    } catch (err) {
      console.warn("xterm fit addon:", err);
    }

    term.open(host);
    state.term = term;
    state.termFit = fitAddon;
    state.termLine = "";
    state.termCursor = 0;
    state.termHistory = state.termHistory || [];
    state.termHistIdx = -1;
    state.termCwd = state.termCwd || "/";
    state.termBusy = false;

    term.writeln("\x1b[1;36mAva Shell\x1b[0m — Shizuku/Root");
    term.writeln("Type \x1b[33mhelp\x1b[0m for builtins.");
    term.writeln("");
    writeShellPrompt();

    term.onData((data) => onShellTermData(data));
    term.onResize(() => {});

    fitShellTerminal();
    window.addEventListener("resize", fitShellTerminal);
    setTimeout(fitShellTerminal, 50);
    setTimeout(fitShellTerminal, 250);
    try { term.focus(); } catch (_) {}
  }

  function fitShellTerminal() {
    if (!state.termFit || !state.term) return;
    try {
      state.termFit.fit();
    } catch (_) {}
  }

  function writeShellPrompt() {
    termWrite(`\x1b[1;32m${shellPromptText()}\x1b[0m`);
  }

  function redrawShellLine() {
    // Move to start of line input: CR then prompt + buffer, clear to end
    termWrite("\r\x1b[0K");
    termWrite(`\x1b[1;32m${shellPromptText()}\x1b[0m${state.termLine || ""}`);
    const back = (state.termLine || "").length - (state.termCursor || 0);
    if (back > 0) termWrite(`\x1b[${back}D`);
  }

  function clearShellTerminal() {
    if (!state.term) {
      ensureShellTerminal();
      return;
    }
    state.term.clear();
    state.termLine = "";
    state.termCursor = 0;
    writeShellPrompt();
    try { state.term.focus(); } catch (_) {}
  }

  function onShellTermData(data) {
    if (!state.term) return;
    if (state.termBusy) {
      // Ctrl+C while running
      if (data === "\u0003") {
        state.termBusy = false;
        termWriteln("^C");
        writeShellPrompt();
      }
      return;
    }

    for (let i = 0; i < data.length; i++) {
      const ch = data[i];
      const code = ch.charCodeAt(0);

      // Enter
      if (ch === "\r" || ch === "\n") {
        termWriteln("");
        const line = (state.termLine || "").trimEnd();
        state.termLine = "";
        state.termCursor = 0;
        state.termHistIdx = -1;
        if (line) {
          if (!state.termHistory.length || state.termHistory[state.termHistory.length - 1] !== line) {
            state.termHistory.push(line);
            if (state.termHistory.length > 200) state.termHistory.shift();
          }
          void runShellLine(line);
        } else {
          writeShellPrompt();
        }
        continue;
      }

      // Ctrl+C
      if (ch === "\u0003") {
        termWriteln("^C");
        state.termLine = "";
        state.termCursor = 0;
        writeShellPrompt();
        continue;
      }

      // Ctrl+L
      if (ch === "\u000c") {
        clearShellTerminal();
        continue;
      }

      // Backspace
      if (ch === "\u007f" || ch === "\b") {
        if (state.termCursor > 0) {
          const s = state.termLine;
          state.termLine = s.slice(0, state.termCursor - 1) + s.slice(state.termCursor);
          state.termCursor -= 1;
          redrawShellLine();
        }
        continue;
      }

      // Escape sequences (arrows)
      if (ch === "\u001b") {
        const seq = data.slice(i);
        if (seq.startsWith("\u001b[A")) { // up
          i += 2;
          if (!state.termHistory.length) continue;
          if (state.termHistIdx < 0) state.termHistIdx = state.termHistory.length;
          state.termHistIdx = Math.max(0, state.termHistIdx - 1);
          state.termLine = state.termHistory[state.termHistIdx] || "";
          state.termCursor = state.termLine.length;
          redrawShellLine();
          continue;
        }
        if (seq.startsWith("\u001b[B")) { // down
          i += 2;
          if (state.termHistIdx < 0) continue;
          state.termHistIdx += 1;
          if (state.termHistIdx >= state.termHistory.length) {
            state.termHistIdx = -1;
            state.termLine = "";
          } else {
            state.termLine = state.termHistory[state.termHistIdx] || "";
          }
          state.termCursor = state.termLine.length;
          redrawShellLine();
          continue;
        }
        if (seq.startsWith("\u001b[C")) { // right
          i += 2;
          if (state.termCursor < (state.termLine || "").length) {
            state.termCursor += 1;
            termWrite("\x1b[C");
          }
          continue;
        }
        if (seq.startsWith("\u001b[D")) { // left
          i += 2;
          if (state.termCursor > 0) {
            state.termCursor -= 1;
            termWrite("\x1b[D");
          }
          continue;
        }
        // swallow unknown ESC sequence start
        continue;
      }

      // printable
      if (code >= 32) {
        const s = state.termLine || "";
        state.termLine = s.slice(0, state.termCursor) + ch + s.slice(state.termCursor);
        state.termCursor += 1;
        redrawShellLine();
      }
    }
  }

  function shellEscapeSingle(s) {
    return String(s || "").replace(/'/g, "'\\''");
  }

  async function execShellRemote(command) {
    const d = findDevice(state.focusDeviceId);
    // Peers: ADB shell only (remote control). Local: Shizuku/root shell.
    if (d && !d.local) {
      const serial = resolveSerial(d);
      if (!serial) {
        return Promise.resolve(new Response(JSON.stringify({
          ok: false,
          error: "need_adb",
          message: t("screenNeedAdb"),
        }), { status: 400, headers: { "Content-Type": "application/json" } }));
      }
      return fetch("/v1/adb/shell", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ command, serial, timeoutSec: 30 }),
      });
    }
    return fetch("/v1/shell/exec", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command, timeoutSec: 30 }),
    });
  }

  async function runShellLine(line) {
    const raw = String(line || "").trim();
    if (!raw) {
      writeShellPrompt();
      return;
    }

    // Local builtins
    if (raw === "help" || raw === "?") {
      termWriteln("Builtins: help, clear, history, cd [dir], pwd");
      termWriteln("Local: Shizuku/Root shell · Peer: wireless ADB shell");
      termWriteln("Keys: ↑/↓ history · Ctrl+C cancel · Ctrl+L clear");
      writeShellPrompt();
      return;
    }
    if (raw === "clear" || raw === "cls") {
      clearShellTerminal();
      return;
    }
    if (raw === "history") {
      (state.termHistory || []).forEach((h, idx) => termWriteln(` ${idx + 1}  ${h}`));
      writeShellPrompt();
      return;
    }
    if (raw === "pwd") {
      termWriteln(state.termCwd || "/");
      writeShellPrompt();
      return;
    }

    let remoteCmd = raw;
    if (raw === "cd" || raw.startsWith("cd ")) {
      const target = raw === "cd" ? "/" : raw.slice(3).trim().replace(/^['"]|['"]$/g, "");
      const dest = target || "/";
      remoteCmd = `cd '${shellEscapeSingle(state.termCwd || "/")}' && cd '${shellEscapeSingle(dest)}' && pwd`;
    } else {
      remoteCmd = `cd '${shellEscapeSingle(state.termCwd || "/")}' && (${raw})`;
    }

    state.termBusy = true;
    try {
      const res = await execShellRemote(remoteCmd);
      const data = await res.json().catch(() => ({}));
      const out = [data.stdout, data.stderr].filter(Boolean).join("\n");
      if (out) {
        // Normalize newlines for xterm
        const normalized = String(out).replace(/\r?\n/g, "\r\n");
        termWrite(normalized);
        if (!normalized.endsWith("\r\n")) termWriteln("");
      }
      if (data.error && !out) {
        termWriteln(`\x1b[31m${data.error}\x1b[0m`);
      }

      if (raw === "cd" || raw.startsWith("cd ")) {
        const pwd = String(data.stdout || "").trim().split("\n").filter(Boolean).pop();
        if (data.ok !== false && data.code === 0 && pwd && pwd.startsWith("/")) {
          state.termCwd = pwd;
        } else if (data.code !== 0) {
          termWriteln(`\x1b[31mcd failed (${data.code})\x1b[0m`);
        }
      } else if (data.code != null && data.code !== 0) {
        termWriteln(`\x1b[90m[exit ${data.code}]\x1b[0m`);
      }
    } catch (err) {
      termWriteln(`\x1b[31m${err?.message || err || "shell_failed"}\x1b[0m`);
    } finally {
      state.termBusy = false;
      writeShellPrompt();
    }
  }

  async function refresh() {
    if (!state.live) return;
    try {
      const res = await fetch("/v1/status", { cache: "no-store" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      render(data);
      // Wall covers must not wait on ADB inventory / reconnect / lost-confirm.
      void refreshAdbFleetDevices(false).then(() => {
        if (state.live && state.status) render(state.status);
      });
    } catch (err) {
      if (!state.live) return;
      renderError(t("statusError"));
      console.warn(err);
    }
  }

  async function refreshDeepTelemetry() {
    if (!state.live) return;
    const d = findDevice(state.focusDeviceId);
    if (d && !d.local) {
      let serial = resolveSerial(d);
      if (!serial) {
        $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbAutoConnecting"))}</div>`;
        serial = await ensurePeerAdb(d, { force: true });
      }
      if (!serial) {
        $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbAutoFail"))}</div>`;
        return;
      }
      $("metricKpis").innerHTML = `<div class="muted">${escapeHtml(t("deviceAdbTelemetryLoading"))}</div>`;
      await loadPeerAdbTelemetry(serial, true);
      return;
    }
    try {
      const res = await fetch("/v1/telemetry", { cache: "no-store" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const tel = await res.json();
      if (state.status) {
        state.status.telemetry = tel;
        render(state.status);
      } else {
        renderTelemetryDetail(tel);
        renderMetricKpis(tel);
        renderCharts(tel);
        if (state.view === "device") {
          renderModules(tel, "modGridFull");
          renderTelemetryDetail(tel);
          renderMetricKpis(tel);
          renderCharts(tel);
        }
      }
    } catch (err) {
      console.warn(err);
    }
  }

  function connectEvents() {
    if (!state.live) return;
    if (typeof EventSource === "undefined") {
      if (!state.refreshTimer) {
        state.refreshTimer = setInterval(() => {
          if (state.live) refresh();
        }, 15000);
      }
      return;
    }
    try {
      state.eventSource?.close();
    } catch (_) {}
    const es = new EventSource("/v1/events");
    state.eventSource = es;
    es.addEventListener("status", (ev) => {
      if (!state.live) return;
      try {
        render(JSON.parse(ev.data));
      } catch (err) {
        console.warn(err);
      }
    });
    es.onerror = () => {};
  }

  function clearScreenTimer() {
    if (state.screenTimer) {
      clearTimeout(state.screenTimer);
      state.screenTimer = null;
    }
  }

  function screenLiveImg() {
    return document.getElementById("screenLiveImg");
  }

  function hasScreenFrame() {
    const img = screenLiveImg();
    return !!(img && img.naturalWidth > 0);
  }

  function setStageState(next) {
    const stage = $("screenStage");
    if (stage) stage.dataset.state = next;
  }

  /** Sync stage status-machine pill from overlay + meta text. */
  function syncScreenStageStatus() {
    const overlay = $("screenOverlay");
    const overlayText = ($("screenOverlayText")?.textContent || "").trim();
    const metaText = ($("screenMeta")?.textContent || "").trim();
    const msg = overlayText || metaText;
    if (!overlay) return;
    if (!msg) {
      overlay.hidden = true;
      return;
    }
    // Prefer a single line in the pill: meta owns steady state; overlay text is transient.
    const line = metaText || overlayText;
    const meta = $("screenMeta");
    const ot = $("screenOverlayText");
    if (meta) meta.textContent = line;
    if (ot) ot.textContent = "";
    overlay.hidden = false;
  }

  function setScreenOverlay(msg) {
    const overlay = $("screenOverlay");
    const text = $("screenOverlayText");
    if (!overlay || !text) return;
    const m = (msg || "").trim();
    if (!m) {
      text.textContent = "";
      // Keep meta-driven status visible if present.
      syncScreenStageStatus();
      return;
    }
    text.textContent = m;
    const meta = $("screenMeta");
    if (meta) meta.textContent = "";
    overlay.hidden = false;
  }

  function setScreenMeta(msg) {
    const meta = $("screenMeta");
    const m = (msg || "").trim();
    if (meta) meta.textContent = m;
    state.screenMetaText = m;
    // Status machine lives on the stage overlay — not a separate footer row.
    const overlay = $("screenOverlay");
    const ot = $("screenOverlayText");
    if (ot) ot.textContent = "";
    if (!overlay) return;
    if (!m) {
      overlay.hidden = true;
      return;
    }
    overlay.hidden = false;
  }

  function setScreenPlaceholderText(msg, isError) {
    const ph = $("screenPlaceholder");
    const text = $("screenPlaceholderText");
    const title = ph?.querySelector(".screen-empty-title");
    // Never cover an existing frame — use overlay for transient status instead.
    if (hasScreenFrame()) {
      if (ph) ph.hidden = true;
      setScreenOverlay(msg);
      setScreenMeta(msg);
      return;
    }
    setScreenOverlay("");
    if (ph) {
      ph.hidden = false;
      ph.classList.toggle("screen-error", !!isError);
    }
    if (text) text.textContent = msg;
    if (title) {
      title.textContent = isError ? t("screenEmptyErrorTitle") : t("screenEmptyTitle");
    }
  }

  function showScreenMessage(msg, isError) {
    teardownLiveImg();
    setScreenOverlay("");
    setStageState("idle");
    const ph = $("screenPlaceholder");
    const text = $("screenPlaceholderText");
    const title = ph?.querySelector(".screen-empty-title");
    if (ph) {
      ph.hidden = false;
      ph.classList.toggle("screen-error", !!isError);
    }
    if (text) text.textContent = msg;
    if (title) {
      title.textContent = isError ? t("screenEmptyErrorTitle") : t("screenEmptyTitle");
    }
    setScreenMeta(isError ? msg : "");
  }

  /** Insert (or refresh) the live MJPEG <img>. Browser handles multipart
   *  replacement natively → continuous video feel, no blob-URL churn. */
  function ensureLiveImg() {
    const host = $("screenSurface");
    if (!host) return null;
    let img = screenLiveImg();
    if (!img) {
      img = document.createElement("img");
      img.id = "screenLiveImg";
      img.alt = "device screen";
      img.decoding = "async";
      img.draggable = false;
      bindScreenInput(img);
      img.addEventListener("load", () => {
        // First real frame arrived — kill the placeholder + starting overlay.
        setStageState("live");
        const ph = $("screenPlaceholder");
        if (ph) ph.hidden = true;
        setScreenOverlay("");
        const w = img.naturalWidth;
        const h = img.naturalHeight;
        if (w && h) {
          setScreenMeta(`${t("screenLiveTag")} · ${w}×${h}`);
        }
      });
      img.addEventListener("error", () => {
        // MJPEG stream aborted (agent stopped scrcpy, network glitch). Bounce
        // the src so browser re-requests without a manual Stop→Start.
        if (!state.streaming) return;
        if (state.adbScreen || state.a11yScreen || state.clusterScreen) return; // poll owns retries
        setScreenMeta(t("screenReconnecting"));
        setTimeout(() => {
          if (state.streaming && !state.adbScreen && !state.a11yScreen && !state.clusterScreen && screenLiveImg() === img) {
            img.src = buildMjpegUrl();
          }
        }, 800);
      });
      host.appendChild(img);
    }
    return img;
  }

  function teardownLiveImg() {
    const img = screenLiveImg();
    if (img) {
      // Blank the src first so the browser closes the multipart connection
      // rather than the socket lingering.
      img.removeAttribute("src");
      img.remove();
    }
  }

  function buildMjpegUrl(intervalMs = 180) {
    // Local hub only — peers use ADB screen poll, never peer HTTP scrcpy.
    const ms = Math.max(80, Math.min(2500, Number(intervalMs) || 180));
    const base = `/v1/screen/mjpeg?intervalMs=${ms}&oneshot=1&_=${Date.now()}`;
    return `${base}&token=${encodeFleetPassword()}`;
  }

  function buildLocalOneshotUrl(force = true) {
    return `/v1/screen/frame?oneshot=1&maxWidth=720&quality=45${force ? "&force=1" : ""}&_=${Date.now()}`;
  }

  function focusAdbSerial() {
    return resolveSerial(findDevice(state.focusDeviceId));
  }

  function buildAdbScreenUrl(force = true) {
    const serial = focusAdbSerial();
    if (!serial) return "";
    return `/v1/adb/screen?serial=${encodeURIComponent(serial)}&maxWidth=720&quality=45${force ? "&force=1" : ""}&_=${Date.now()}`;
  }

  async function startAdbScreen() {
    const serial = focusAdbSerial();
    if (!serial) {
      showScreenMessage(t("adbNeedHostPort"), true);
      return;
    }
    setStageState("starting");
    setScreenPlaceholderText(t("screenAdbStarting"), false);
    setScreenOverlay(t("screenAdbStarting"));
    setScreenMeta(t("screenAdbStarting"));
    $("screenStartBtn").disabled = true;
    $("screenStopBtn").disabled = false;
    state.streaming = true;
    state.adbScreen = true;
    state.a11yScreen = false;
    state.clusterScreen = false;
    state.scrcpyOwned = false;
    state.scrcpyLive = false;
    clearScreenTimer();
    state.screenFailStreak = 0;
    state.screenFetchGen += 1;
    const gen = state.screenFetchGen;
    const img = ensureLiveImg();

    const tick = async () => {
      if (!state.streaming || state.adbScreen !== true || gen !== state.screenFetchGen) return;
      const url = buildAdbScreenUrl(true);
      if (!url || !img) return;
      try {
        const res = await fetch(url, { cache: "no-store" });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        if (!blob?.size || !(blob.type || "").includes("jpeg")) throw new Error("not_jpeg");
        const obj = URL.createObjectURL(blob);
        const prev = img.dataset.blobUrl;
        img.onload = () => {
          setStageState("live");
          const ph = $("screenPlaceholder");
          if (ph) ph.hidden = true;
          setScreenOverlay("");
          const w = img.naturalWidth;
          const h = img.naturalHeight;
          setScreenMeta(w && h ? `${t("screenAdbLive")} · ${w}×${h}` : t("screenAdbLive"));
        };
        img.src = obj;
        img.dataset.blobUrl = obj;
        if (prev) {
          try { URL.revokeObjectURL(prev); } catch (_) {}
        }
        state.screenFailStreak = 0;
      } catch (err) {
        state.screenFailStreak = (state.screenFailStreak || 0) + 1;
        setScreenMeta(`${t("screenAdbLive")} · ${t("screenReconnecting")}`);
        if (state.screenFailStreak >= 3 && !hasScreenFrame()) {
          showScreenMessage(t("screenAdbFail"), true);
          state.streaming = false;
          state.adbScreen = false;
          $("screenStartBtn").disabled = false;
          $("screenStopBtn").disabled = true;
          return;
        }
      }
      state.screenTimer = setTimeout(tick, 900);
    };

    $("screenStartBtn").disabled = false;
    void tick();
  }

  async function startLocalA11yScreen() {
    setStageState("starting");
    setScreenPlaceholderText(t("screenA11yStarting"), false);
    setScreenOverlay(t("screenA11yStarting"));
    setScreenMeta(t("screenA11yStarting"));
    $("screenStartBtn").disabled = true;
    $("screenStopBtn").disabled = false;
    state.streaming = true;
    state.adbScreen = false;
    state.a11yScreen = true;
    state.clusterScreen = false;
    state.scrcpyOwned = false;
    state.scrcpyLive = false;
    clearScreenTimer();
    state.screenFailStreak = 0;
    state.screenFetchGen += 1;
    const gen = state.screenFetchGen;
    const img = ensureLiveImg();

    const tick = async () => {
      if (!state.streaming || state.a11yScreen !== true || gen !== state.screenFetchGen) return;
      if (!img) return;
      try {
        const res = await fetch(buildLocalOneshotUrl(true), { cache: "no-store" });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        if (!blob?.size || !(blob.type || "").includes("jpeg")) throw new Error("not_jpeg");
        const obj = URL.createObjectURL(blob);
        const prev = img.dataset.blobUrl;
        img.onload = () => {
          setStageState("live");
          const ph = $("screenPlaceholder");
          if (ph) ph.hidden = true;
          setScreenOverlay("");
          const w = img.naturalWidth;
          const h = img.naturalHeight;
          setScreenMeta(w && h ? `${t("screenModeA11y")} · ${w}×${h}` : t("screenModeA11y"));
        };
        img.src = obj;
        img.dataset.blobUrl = obj;
        if (prev) {
          try { URL.revokeObjectURL(prev); } catch (_) {}
        }
        state.screenFailStreak = 0;
      } catch (err) {
        state.screenFailStreak = (state.screenFailStreak || 0) + 1;
        setScreenMeta(`${t("screenModeA11y")} · ${t("screenReconnecting")}`);
        if (state.screenFailStreak >= 3 && !hasScreenFrame()) {
          showScreenMessage(t("screenA11yFail"), true);
          state.streaming = false;
          state.a11yScreen = false;
          $("screenStartBtn").disabled = false;
          $("screenStopBtn").disabled = true;
          return;
        }
      }
      // Match Accessibility takeScreenshot rate limit (~1.5s).
      state.screenTimer = setTimeout(tick, 1600);
    };

    $("screenStartBtn").disabled = false;
    void tick();
  }

  async function startClusterScreen() {
    const d = findDevice(state.focusDeviceId);
    const url0 = deviceClusterScreenUrl(d, { wall: false, force: true });
    if (!url0) {
      showScreenMessage(t("screenNeedAdb"), true);
      return;
    }
    setStageState("starting");
    setScreenPlaceholderText(t("screenModeCluster"), false);
    setScreenOverlay(t("screenModeCluster"));
    setScreenMeta(t("screenModeCluster"));
    $("screenStartBtn").disabled = true;
    $("screenStopBtn").disabled = false;
    state.streaming = true;
    state.adbScreen = false;
    state.a11yScreen = false;
    state.clusterScreen = true;
    state.scrcpyOwned = false;
    state.scrcpyLive = false;
    clearScreenTimer();
    state.screenFailStreak = 0;
    state.screenFetchGen += 1;
    const gen = state.screenFetchGen;
    const img = ensureLiveImg();

    const tick = async () => {
      if (!state.streaming || state.clusterScreen !== true || gen !== state.screenFetchGen) return;
      if (!img) return;
      const focus = findDevice(state.focusDeviceId);
      const url = deviceClusterScreenUrl(focus, { wall: false, force: true });
      if (!url) {
        showScreenMessage(t("screenNeedAdb"), true);
        state.streaming = false;
        state.clusterScreen = false;
        $("screenStartBtn").disabled = false;
        $("screenStopBtn").disabled = true;
        return;
      }
      try {
        const res = await fetch(url, { cache: "no-store" });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        if (!blob?.size || !(blob.type || "").includes("jpeg")) throw new Error("not_jpeg");
        const obj = URL.createObjectURL(blob);
        const prev = img.dataset.blobUrl;
        img.onload = () => {
          setStageState("live");
          const ph = $("screenPlaceholder");
          if (ph) ph.hidden = true;
          setScreenOverlay("");
          const w = img.naturalWidth;
          const h = img.naturalHeight;
          setScreenMeta(w && h ? `${t("screenModeCluster")} · ${w}×${h}` : t("screenModeCluster"));
        };
        img.src = obj;
        img.dataset.blobUrl = obj;
        if (prev) {
          try { URL.revokeObjectURL(prev); } catch (_) {}
        }
        state.screenFailStreak = 0;
      } catch (err) {
        state.screenFailStreak = (state.screenFailStreak || 0) + 1;
        setScreenMeta(`${t("screenModeCluster")} · ${t("screenReconnecting")}`);
        if (state.screenFailStreak >= 3 && !hasScreenFrame()) {
          showScreenMessage(t("screenA11yFail"), true);
          state.streaming = false;
          state.clusterScreen = false;
          $("screenStartBtn").disabled = false;
          $("screenStopBtn").disabled = true;
          return;
        }
      }
      state.screenTimer = setTimeout(tick, 1600);
    };

    $("screenStartBtn").disabled = false;
    void tick();
  }

  async function startScreen() {
    const s = state.status;
    const screen = s?.screen || {};
    const shot = screen.oneshot || {};
    const sc = screen.scrcpy || {};
    const canShell = !!(screen.captureViaShell || sc.canLaunch || shot.canShellCapture);
    const canA11y = !!(screen.captureViaAccessibility || shot.canAccessibilityCapture);
    const can = !!(screen.available || shot.canCapture || canShell || canA11y || sc.canLaunch);
    const d = findDevice(state.focusDeviceId);

    // Peers: live ADB mirror, else Ava agent oneshot. Never browser→peer :8888.
    if (d && !d.local) {
      if (hasLiveAdb(d)) {
        await startAdbScreen();
        return;
      }
      if (hasAdvertisedAgent(d)) {
        await startClusterScreen();
        return;
      }
      showScreenMessage(t("screenNeedAdb"), true);
      return;
    }

    // Local hub without shell: Accessibility oneshot poll (no MediaProjection).
    if (!canShell && canA11y) {
      await startLocalA11yScreen();
      return;
    }

    if (!can) {
      showScreenMessage(t("screenNeedA11yOrShell"), true);
      const a11yBtn = $("screenEnableA11yBtn");
      if (a11yBtn) a11yBtn.classList.remove("hidden");
      return;
    }

    setStageState("starting");
    setScreenPlaceholderText(t("screenStartingScrcpy"), false);
    setScreenOverlay(t("screenStartingScrcpy"));
    setScreenMeta(t("screenStartingScrcpy"));
    $("screenStartBtn").disabled = true;
    $("screenStopBtn").disabled = false;
    state.streaming = false;
    state.adbScreen = false;
    state.a11yScreen = false;
    state.clusterScreen = false;
    clearScreenTimer();
    state.screenFailStreak = 0;
    state.screenFetchGen += 1;
    const gen = state.screenFetchGen;

    try {
      // Kick scrcpy asynchronously — MJPEG falls back to oneshot until live frames.
      const startPromise = fetch("/v1/screen/scrcpy/start", { method: "POST" });

      state.streaming = true;
      state.scrcpyOwned = true;
      state.scrcpyLive = true;
      const img = ensureLiveImg();
      if (img) img.src = buildMjpegUrl(180);

      const startRes = await startPromise;
      if (gen !== state.screenFetchGen) return;
      const startData = await startRes.json().catch(() => ({}));
      if (!startRes.ok || startData.ok === false) {
        const err = startData.error || startData.lastError || "scrcpy_start_failed";
        // Shell scrcpy failed but a11y capture may still work — degrade.
        if (!hasScreenFrame() && canA11y) {
          state.scrcpyOwned = false;
          state.scrcpyLive = false;
          state.streaming = false;
          await startLocalA11yScreen();
          return;
        }
        if (!hasScreenFrame()) {
          showScreenMessage(
            err.includes("need_shizuku") || err.includes("accessibility")
              ? t("screenNeedA11yOrShell")
              : `${t("screenScrcpyStartFail")}: ${err}`,
            true,
          );
          $("screenStartBtn").disabled = false;
          $("screenStopBtn").disabled = true;
          state.streaming = false;
          state.scrcpyOwned = false;
          state.scrcpyLive = false;
          return;
        }
        setScreenMeta(`${t("screenScrcpyStartFail")}: ${err}`);
      } else {
        setScreenMeta(t("screenScrcpyRunning"));
      }
      $("screenStartBtn").disabled = false;
    } catch (err) {
      console.warn("screen start:", err);
      if (canA11y) {
        await startLocalA11yScreen();
        return;
      }
      showScreenMessage(t("screenFrameError"), true);
      $("screenStartBtn").disabled = false;
      $("screenStopBtn").disabled = true;
      state.streaming = false;
      state.scrcpyOwned = false;
      state.scrcpyLive = false;
    }
  }

  function stopScreen() {
    const wasStreaming = state.streaming;
    const wasAdb = state.adbScreen;
    const wasA11y = state.a11yScreen;
    const wasCluster = state.clusterScreen;
    state.streaming = false;
    state.adbScreen = false;
    state.a11yScreen = false;
    state.clusterScreen = false;
    state.screenFailStreak = 0;
    state.screenFetchGen += 1;
    clearScreenTimer();
    // Ava scrcpy only — never POST stop to an ADB peer host / a11y / cluster poll.
    if (!wasAdb && !wasA11y && !wasCluster && (wasStreaming || state.scrcpyOwned || state.scrcpyLive)) {
      state.scrcpyOwned = false;
      state.scrcpyLive = false;
      state.scrcpyStarting = false;
      fetch("/v1/screen/scrcpy/stop", { method: "POST" }).catch(() => {});
    } else {
      state.scrcpyOwned = false;
      state.scrcpyLive = false;
      state.scrcpyStarting = false;
    }
    const live = screenLiveImg();
    if (live?.dataset?.blobUrl) {
      try { URL.revokeObjectURL(live.dataset.blobUrl); } catch (_) {}
      delete live.dataset.blobUrl;
    }
    $("screenStartBtn").disabled = false;
    $("screenStopBtn").disabled = true;
    teardownLiveImg();
    setStageState("idle");
    setScreenOverlay("");
    setScreenPlaceholderText(t("screenWaiting"), false);
    setScreenMeta("");
  }

  // Removed click-based tap — pointer gestures own screen input.
  function screenContentRect(img) {
    const rect = img.getBoundingClientRect();
    const nw = img.naturalWidth || 0;
    const nh = img.naturalHeight || 0;
    if (!nw || !nh || rect.width <= 0 || rect.height <= 0) {
      return { left: rect.left, top: rect.top, width: rect.width, height: rect.height };
    }
    const scale = Math.min(rect.width / nw, rect.height / nh);
    const w = nw * scale;
    const h = nh * scale;
    return {
      left: rect.left + (rect.width - w) / 2,
      top: rect.top + (rect.height - h) / 2,
      width: w,
      height: h,
    };
  }

  function eventToNorm(ev, img, clampOutside) {
    const r = screenContentRect(img);
    if (r.width <= 0 || r.height <= 0) return null;
    let nx = (ev.clientX - r.left) / r.width;
    let ny = (ev.clientY - r.top) / r.height;
    if (!clampOutside && (nx < 0 || nx > 1 || ny < 0 || ny > 1)) return null;
    return {
      nx: Math.min(1, Math.max(0, nx)),
      ny: Math.min(1, Math.max(0, ny)),
    };
  }

  function devicePxFromNorm(nx, ny, img) {
    const screen = (state.status && state.status.screen) || {};
    let deviceW = Number(screen.displayWidth) || 0;
    let deviceH = Number(screen.displayHeight) || 0;
    const nw = img.naturalWidth || 0;
    const nh = img.naturalHeight || 0;
    // Frame is what the user clicked. If status still reports the other
    // orientation (Application context metrics often stick after rotate), swap.
    if (deviceW > 0 && deviceH > 0 && nw > 0 && nh > 0) {
      if ((deviceW > deviceH) !== (nw > nh)) {
        const swap = deviceW;
        deviceW = deviceH;
        deviceH = swap;
      }
    } else {
      deviceW = deviceW || nw;
      deviceH = deviceH || nh;
    }
    // Match server resolveDisplayPoint: nx * (displaySize - 1).
    const maxX = Math.max(0, deviceW - 1);
    const maxY = Math.max(0, deviceH - 1);
    return {
      x: Math.max(0, Math.min(maxX, Math.round(nx * maxX))),
      y: Math.max(0, Math.min(maxY, Math.round(ny * maxY))),
      deviceW,
      deviceH,
    };
  }

  const SCREEN_MOVE_PX = 10; // below this → tap; above → swipe/drag
  let screenGesture = null;
  let screenInputBusy = false;

  function bindScreenInput(img) {
    // RMB / trackpad secondary → Android Back (debounced; not on pointerdown).
    img.addEventListener("contextmenu", onScreenContextMenu);
    img.addEventListener("dragstart", (e) => e.preventDefault());
    img.addEventListener("pointerdown", onScreenPointerDown);
    img.addEventListener("pointermove", onScreenPointerMove);
    img.addEventListener("pointerup", onScreenPointerUp);
    img.addEventListener("pointercancel", onScreenPointerCancel);
    img.addEventListener("lostpointercapture", onScreenPointerCancel);
  }

  function onScreenContextMenu(ev) {
    ev.preventDefault();
    ev.stopPropagation();
    if (!state.streaming) return;
    requestScreenBack();
  }

  function onScreenPointerDown(ev) {
    if (!state.streaming) return;
    const img = screenLiveImg();
    if (!img || !img.naturalWidth) return;

    // Secondary mouse button / ctrl-click: arm Back; fire on pointerup or contextmenu.
    const secondary =
      ev.button === 2 ||
      ev.buttons === 2 ||
      (ev.pointerType === "mouse" && ev.button === 0 && ev.ctrlKey);
    if (secondary) {
      ev.preventDefault();
      ev.stopPropagation();
      screenGesture = { pointerId: ev.pointerId, secondary: true, t0: performance.now() };
      return;
    }
    // Only primary button / touch / pen.
    if (ev.button !== 0 && ev.pointerType === "mouse") return;

    const pos = eventToNorm(ev, img, false);
    if (!pos) return; // letterbox click — ignore

    ev.preventDefault();
    screenGesture = {
      pointerId: ev.pointerId,
      startX: ev.clientX,
      startY: ev.clientY,
      nx1: pos.nx,
      ny1: pos.ny,
      nx2: pos.nx,
      ny2: pos.ny,
      moved: false,
      secondary: false,
      t0: performance.now(),
    };
    try {
      img.setPointerCapture(ev.pointerId);
    } catch (_) { /* some browsers reject capture mid-gesture */ }
  }

  function onScreenPointerMove(ev) {
    const g = screenGesture;
    if (!g || g.pointerId !== ev.pointerId || g.secondary) return;
    const img = screenLiveImg();
    if (!img) return;
    const pos = eventToNorm(ev, img, true);
    if (!pos) return;
    g.nx2 = pos.nx;
    g.ny2 = pos.ny;
    const dx = ev.clientX - g.startX;
    const dy = ev.clientY - g.startY;
    if (!g.moved && (dx * dx + dy * dy) >= SCREEN_MOVE_PX * SCREEN_MOVE_PX) {
      g.moved = true;
    }
  }

  function onScreenPointerUp(ev) {
    const g = screenGesture;
    if (!g || g.pointerId !== ev.pointerId) return;
    screenGesture = null;
    const img = screenLiveImg();
    if (!img || !state.streaming) return;

    if (g.secondary || ev.button === 2) {
      requestScreenBack();
      return;
    }

    // Final sample (clamped) so drag-out still lands on an edge pixel.
    const pos = eventToNorm(ev, img, true);
    if (pos) {
      g.nx2 = pos.nx;
      g.ny2 = pos.ny;
    }
    const elapsed = Math.max(1, performance.now() - g.t0);
    if (g.moved) {
      // Keep under a11y gesture wait (duration + slack ≤ ~4s).
      const durationMs = Math.round(Math.min(2000, Math.max(80, elapsed)));
      sendScreenSwipe(g.nx1, g.ny1, g.nx2, g.ny2, durationMs, img);
    } else {
      sendScreenTap(g.nx1, g.ny1, img);
    }
  }

  function onScreenPointerCancel(ev) {
    if (screenGesture && (!ev || screenGesture.pointerId === ev.pointerId)) {
      screenGesture = null;
    }
  }

  async function sendScreenTap(nx, ny, img) {
    if (screenInputBusy) return;
    const { x, y } = devicePxFromNorm(nx, ny, img);
    setScreenMeta(`tap ${x},${y}…`);
    screenInputBusy = true;
    try {
      let res;
      if (state.adbScreen) {
        const serial = focusAdbSerial();
        if (!serial) {
          setScreenMeta(t("screenNeedAdb"));
          return;
        }
        const q = `serial=${encodeURIComponent(serial)}&x=${x}&y=${y}`;
        res = await fetch(`/v1/adb/input/tap?${q}`, { method: "POST" });
      } else {
        const q = `nx=${nx.toFixed(5)}&ny=${ny.toFixed(5)}&x=${x}&y=${y}`;
        res = await fetch(`/v1/input/tap?${q}`, { method: "POST" });
      }
      const data = await res.json().catch(() => ({}));
      if (data && data.ok) {
        const via = data.via ? ` · ${data.via}` : "";
        setScreenMeta(`${t("tapOk")} (${data.x ?? x},${data.y ?? y})${via}`);
      } else {
        setScreenMeta(`${t("tapFail")}: ${data?.error || `HTTP ${res.status}`}`);
      }
    } catch (_) {
      setScreenMeta(t("tapFail"));
    } finally {
      screenInputBusy = false;
    }
  }

  async function sendScreenSwipe(nx1, ny1, nx2, ny2, durationMs, img) {
    if (screenInputBusy) return;
    const a = devicePxFromNorm(nx1, ny1, img);
    const b = devicePxFromNorm(nx2, ny2, img);
    setScreenMeta(`swipe ${a.x},${a.y}→${b.x},${b.y}…`);
    screenInputBusy = true;
    try {
      let res;
      if (state.adbScreen) {
        const serial = focusAdbSerial();
        if (!serial) {
          setScreenMeta(t("screenNeedAdb"));
          return;
        }
        const q = [
          `serial=${encodeURIComponent(serial)}`,
          `x1=${a.x}`, `y1=${a.y}`, `x2=${b.x}`, `y2=${b.y}`,
          `durationMs=${durationMs}`,
        ].join("&");
        res = await fetch(`/v1/adb/input/swipe?${q}`, { method: "POST" });
      } else {
        const q = [
          `nx1=${nx1.toFixed(5)}`, `ny1=${ny1.toFixed(5)}`,
          `nx2=${nx2.toFixed(5)}`, `ny2=${ny2.toFixed(5)}`,
          `x1=${a.x}`, `y1=${a.y}`, `x2=${b.x}`, `y2=${b.y}`,
          `durationMs=${durationMs}`,
        ].join("&");
        res = await fetch(`/v1/input/swipe?${q}`, { method: "POST" });
      }
      const data = await res.json().catch(() => ({}));
      if (data && data.ok) {
        const via = data.via ? ` · ${data.via}` : "";
        setScreenMeta(`${t("swipeOk")} ${a.x},${a.y}→${b.x},${b.y}${via}`);
      } else {
        setScreenMeta(`${t("swipeFail")}: ${data?.error || `HTTP ${res.status}`}`);
      }
    } catch (_) {
      setScreenMeta(t("swipeFail"));
    } finally {
      screenInputBusy = false;
    }
  }

  function requestScreenBack() {
    const now = Date.now();
    if (now - (state.screenBackAt || 0) < 400) return;
    state.screenBackAt = now;
    void sendScreenBack();
  }

  async function sendScreenBack() {
    // Back must not be dropped while a tap/swipe round-trip is in flight.
    if (!state.streaming) return;
    setScreenMeta(`${t("backSending")}…`);
    try {
      let res;
      if (state.adbScreen) {
        const serial = focusAdbSerial();
        if (!serial) {
          setScreenMeta(t("screenNeedAdb"));
          return;
        }
        res = await fetch(`/v1/adb/input/key?serial=${encodeURIComponent(serial)}&key=BACK`, { method: "POST" });
      } else {
        res = await fetch("/v1/input/key?key=BACK", { method: "POST" });
      }
      const data = await res.json().catch(() => ({}));
      if (data && data.ok) {
        const via = data.via ? ` · ${data.via}` : "";
        setScreenMeta(`${t("backOk")}${via}`);
      } else {
        setScreenMeta(`${t("backFail")}: ${data?.error || `HTTP ${res.status}`}`);
      }
    } catch (_) {
      setScreenMeta(t("backFail"));
    }
  }

  async function openAccessibilitySettings() {
    // User-initiated only — never call from auto-retry / Start failure loops.
    try {
      const res = await fetch("/v1/accessibility/open-settings", { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (data && data.ok) {
        setScreenMeta(t("screenA11ySettingsOpened"));
      } else {
        setScreenMeta(t("screenA11ySettingsFail"));
      }
    } catch (_) {
      setScreenMeta(t("screenA11ySettingsFail"));
    }
  }

  // ── Settings ──────────────────────────────────────

  function isPrimitiveSetting(val) {
    return val === null || typeof val === "boolean" || typeof val === "number" || typeof val === "string";
  }

  function isComplexSetting(val) {
    return Array.isArray(val) || (val != null && typeof val === "object");
  }

  function humanizeKey(key) {
    return String(key ?? "")
      .replace(/_/g, " ")
      .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
      .replace(/\bHa\b/g, "HA")
      .replace(/\bUrl\b/g, "URL")
      .replace(/\bId\b/g, "ID")
      .replace(/\bDb\b/g, "dB")
      .replace(/\bAec\b/g, "AEC")
      .replace(/\bEsphome\b/g, "ESPHome")
      .replace(/^./, (c) => c.toUpperCase());
  }

  function fieldNameFromKey(key) {
    const dot = String(key).lastIndexOf(".");
    return dot >= 0 ? key.slice(dot + 1) : key;
  }

  function partitionLabel(key) {
    const i18nKey = `partition.${key}`;
    const localized = t(i18nKey);
    return localized !== i18nKey ? localized : humanizeKey(key);
  }

  function fieldLabel(name, def, fullKey) {
    // Always prefer i18n so English schema labels never lock the UI language.
    if (fullKey) {
      const qualified = `field.${fullKey}`;
      const qualifiedLoc = t(qualified);
      if (qualifiedLoc !== qualified) return qualifiedLoc;
    }
    const i18nKey = `field.${name}`;
    const localized = t(i18nKey);
    if (localized !== i18nKey) return localized;
    const fromDef = def?.label;
    if (fromDef && String(fromDef).trim() && fromDef !== name) return String(fromDef);
    return humanizeKey(name);
  }

  function fieldDescription(name, def, fullKey) {
    if (fullKey) {
      const qualified = `fieldDesc.${fullKey}`;
      const qualifiedLoc = t(qualified);
      if (qualifiedLoc !== qualified) return qualifiedLoc;
    }
    const i18nKey = `fieldDesc.${name}`;
    const localized = t(i18nKey);
    if (localized !== i18nKey) return localized;
    const fromDef = def?.description;
    if (fromDef && String(fromDef).trim()) return String(fromDef);
    return "";
  }

  function optionLabel(fieldName, value, fallbackMap) {
    const v = String(value);
    const i18nKey = `option.${fieldName}.${v}`;
    const localized = t(i18nKey);
    if (localized !== i18nKey) return localized;
    if (fallbackMap && fallbackMap[v] != null) return String(fallbackMap[v]);
    return v;
  }

  /** Ava VoiceAccentColors — stored as "" | voice_rainbow_* | #RRGGBB */
  const VOICE_ACCENT_RAINBOW = [
    ["voice_rainbow_red", "#FF4757"],
    ["voice_rainbow_orange", "#FF9500"],
    ["voice_rainbow_yellow", "#FFD60A"],
    ["voice_rainbow_green", "#34C759"],
    ["voice_rainbow_cyan", "#00D4FF"],
    ["voice_rainbow_blue", "#007AFF"],
    ["voice_rainbow_purple", "#AF52DE"],
  ];

  function isVoiceAccentField(name) {
    return name === "voiceWakeWord1AccentColor" || name === "voiceWakeWord2AccentColor";
  }

  function voiceAccentDefaultHex(name) {
    return name === "voiceWakeWord2AccentColor" ? "#00D4FF" : "#00FF88";
  }

  function normalizeVoiceAccentHex(raw) {
    const s = String(raw ?? "").trim();
    if (!s) return "";
    const withHash = s.startsWith("#") ? s : `#${s}`;
    if (/^#[0-9A-Fa-f]{6}$/i.test(withHash)) {
      return `#${withHash.slice(1).toUpperCase()}`;
    }
    return "";
  }

  function resolveVoiceAccentDisplayHex(name, val) {
    const raw = String(val ?? "").trim();
    if (/^#[0-9A-Fa-f]{6}$/i.test(raw)) return `#${raw.slice(1).toUpperCase()}`;
    const preset = VOICE_ACCENT_RAINBOW.find(([k]) => k === raw);
    if (preset) return preset[1];
    return voiceAccentDefaultHex(name);
  }

  function inferFieldDef(name, val) {
    const label = fieldLabel(name, null);
    if (isVoiceAccentField(name)) return { type: "voiceAccent", label };
    if (typeof val === "boolean") return { type: "bool", label };
    if (typeof val === "number") return { type: "number", label };
    if (isComplexSetting(val)) return { type: "json", label };
    return { type: "text", label };
  }

  function schemaFieldDef(partition, field) {
    const fields = state.schema?.fields;
    if (!Array.isArray(fields)) return null;
    const hit = fields.find((f) => f?.partition === partition && f?.field === field);
    if (!hit || !hit.type) return null;
    if (hit.type === "select" && Array.isArray(hit.options)) {
      return {
        type: "select",
        label: hit.label || fieldLabel(field, hit),
        options: hit.options,
        optionLabels: hit.optionLabels || null,
        valueType: hit.valueType || "string",
        description: hit.description,
      };
    }
    if (hit.type === "number") {
      return {
        type: "number",
        label: hit.label || fieldLabel(field, hit),
        min: hit.min,
        max: hit.max,
        valueType: "number",
        description: hit.description,
      };
    }
    return null;
  }

  function resolveFieldDef(key, val) {
    const dot = key.indexOf(".");
    const name = dot > 0 ? key.slice(dot + 1) : key;
    // Always use accent editor for wake-word feedback colors (preset key or #RRGGBB).
    if (isVoiceAccentField(name)) {
      return { type: "voiceAccent", label: fieldLabel(name, null) };
    }
    if (dot > 0) {
      const fromSchema = schemaFieldDef(key.slice(0, dot), key.slice(dot + 1));
      if (fromSchema) return fromSchema;
    }
    return inferFieldDef(name, val);
  }

  function isNestedSettingsShape(settings) {
    if (!settings || typeof settings !== "object") return false;
    return Object.values(settings).some((v) => v && typeof v === "object" && !Array.isArray(v));
  }

  function markSettingsDirty(dirty = true) {
    state.settingsDirty = !!dirty;
  }

  function setJsonSyncStatus(kind) {
    const el = $("jsonSyncStatus");
    if (!el) return;
    el.dataset.state = kind;
    const key =
      kind === "editing" ? "settingsJsonEditing"
        : kind === "error" ? "settingsJsonError"
          : kind === "dirty" ? "settingsJsonPending"
            : "settingsJsonSynced";
    el.textContent = t(key);
  }

  function stringifySettings() {
    return JSON.stringify(state.settings || {}, null, 2);
  }

  function pushSettingsToJsonEditor({ force = false } = {}) {
    const editor = $("jsonEditor");
    if (!editor) return;
    if (!force && (state.jsonEditing || document.activeElement === editor)) return;
    state.jsonSuppress = true;
    editor.value = stringifySettings();
    state.jsonSuppress = false;
    setJsonSyncStatus(state.settingsDirty ? "dirty" : "synced");
  }

  function captureCatalogOpenState() {
    const open = new Set();
    document.querySelectorAll("#settingsCatalog details[data-group]").forEach((el) => {
      if (el.open) open.add(el.dataset.group);
    });
    document.querySelectorAll("#settingsCatalog details[data-partition]").forEach((el) => {
      if (el.open) open.add(`p:${el.dataset.partition}`);
    });
    return open;
  }

  function applyCatalogOpenState(open) {
    if (!open || !open.size) return;
    // Avoid firing a cascade of "toggle" events while restoring open state.
    document.querySelectorAll("#settingsCatalog details[data-group]").forEach((el) => {
      const want = open.has(el.dataset.group);
      if (el.open !== want) el.open = want;
    });
    document.querySelectorAll("#settingsCatalog details[data-partition]").forEach((el) => {
      const want = open.has(`p:${el.dataset.partition}`);
      if (el.open !== want) el.open = want;
    });
  }

  function setCatalogExpanded(expanded) {
    document.querySelectorAll("#settingsCatalog details").forEach((el) => {
      el.open = !!expanded;
    });
    if (!expanded) syncSettingsMiniNav(state.settingsFocusGroup || null);
    else syncSettingsMiniNav(state.settingsFocusGroup || null, { keepMultiOpen: true });
  }

  /** Ava-settings-home style mini icons (monochrome SVG). */
  const SETTINGS_NAV_ICON = {
    voice: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M12 3a3 3 0 0 0-3 3v5a3 3 0 1 0 6 0V6a3 3 0 0 0-3-3zm-7 8a1 1 0 0 1 2 0 5 5 0 0 0 10 0 1 1 0 1 1 2 0 7 7 0 0 1-6 6.93V21a1 1 0 1 1-2 0v-3.07A7 7 0 0 1 5 11z"/></svg>`,
    extensions: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M4 5a2 2 0 0 1 2-2h5v7H4V5zm9-2h5a2 2 0 0 1 2 2v3h-7V3zm7 7v7a2 2 0 0 1-2 2h-5v-9h7zM11 12v9H6a2 2 0 0 1-2-2v-7h7z"/></svg>`,
    service: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M19.14 12.94c.04-.31.06-.63.06-.94s-.02-.63-.06-.94l2.03-1.58a.5.5 0 0 0 .12-.64l-1.92-3.32a.5.5 0 0 0-.6-.22l-2.39.96a7.2 7.2 0 0 0-1.63-.94l-.36-2.54A.5.5 0 0 0 13.9 2h-3.8a.5.5 0 0 0-.5.42l-.36 2.54c-.59.24-1.13.55-1.63.94l-2.39-.96a.5.5 0 0 0-.6.22L2.7 8.48a.5.5 0 0 0 .12.64l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94L2.82 14.6a.5.5 0 0 0-.12.64l1.92 3.32c.14.24.43.34.69.22l2.39-.96c.5.39 1.04.7 1.63.94l.36 2.54c.05.24.26.42.5.42h3.8c.24 0 .45-.18.5-.42l.36-2.54c.59-.24 1.13-.55 1.63-.94l2.39.96c.26.12.55.02.69-.22l1.92-3.32a.5.5 0 0 0-.12-.64l-2.03-1.58zM12 15.5A3.5 3.5 0 1 1 12 8a3.5 3.5 0 0 1 0 7.5z"/></svg>`,
    bluetooth: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M17.71 7.71 12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71L13.41 12l4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z"/></svg>`,
    screensaver: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-5v2h2a1 1 0 1 1 0 2H9a1 1 0 1 1 0-2h2v-2H6a2 2 0 0 1-2-2V6zm2 0v9h12V6H6z"/></svg>`,
    browser: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm7.93 9h-3.1a15.4 15.4 0 0 0-1.2-5.01A8.03 8.03 0 0 1 19.93 11zM12 4c.9 0 2.2 1.86 2.9 5H9.1C9.8 5.86 11.1 4 12 4zM4.07 13h3.1c.2 1.8.7 3.5 1.2 5.01A8.03 8.03 0 0 1 4.07 13zm3.1-2h-3.1a8.03 8.03 0 0 1 4.3-5.01C7.87 7.5 7.37 9.2 7.17 11zM12 20c-.9 0-2.2-1.86-2.9-5h5.8c-.7 3.14-2 5-2.9 5zm2.83-7H9.17c.2-1.9.72-3.7 1.35-5h2.96c.63 1.3 1.15 3.1 1.35 5zm.8 5.01c.5-1.51 1-3.21 1.2-5.01h3.1a8.03 8.03 0 0 1-4.3 5.01z"/></svg>`,
    advanced: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M7 2h2v4H7V2zm8 0h2v4h-2V2zM5 8h14v2H5V8zm1 4h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zm0 2v6h12v-6H6z"/></svg>`,
    modstore: `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M4 7a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v2H4V7zm0 4h16v8a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3v-8zm5 2v2h6v-2H9z"/></svg>`,
  };

  function syncSettingsMiniNav(activeId, { keepMultiOpen = false } = {}) {
    const nav = $("settingsMiniNav");
    if (!nav) return;
    nav.querySelectorAll(".settings-mini-item").forEach((btn) => {
      const on = btn.dataset.group === activeId;
      btn.classList.toggle("on", on);
      btn.setAttribute("aria-current", on ? "page" : "false");
    });
    if (!keepMultiOpen && activeId) state.settingsFocusGroup = activeId;
  }

  function buildSettingsMiniNav(groupIds) {
    const nav = $("settingsMiniNav");
    if (!nav) return;
    if (!groupIds.length) {
      nav.innerHTML = "";
      nav.hidden = true;
      return;
    }
    nav.hidden = false;
    nav.innerHTML = groupIds.map((id) => {
      const label = escapeHtml(settingsGroupLabel(id));
      const hint = settingsGroupHint(id);
      const icon = SETTINGS_NAV_ICON[id] || SETTINGS_NAV_ICON.advanced;
      return `
        <button type="button" class="settings-mini-item" data-group="${escapeHtml(id)}" title="${escapeHtml(hint || label)}">
          <span class="settings-mini-icon">${icon}</span>
          <span class="settings-mini-title">${label}</span>
        </button>`;
    }).join("");
    ensureSettingsStickyMetricsHook();
    afterLayout(() => updateSettingsStickyMetrics());
  }

  /** Measure settings-header height for scroll-margin offset. */
  function updateSettingsStickyMetrics() {
    const view = $("view-settings");
    const tools = $("settingsToolsBar");
    if (!view) return 72;
    const toolbarH = tools
      ? Math.ceil(tools.getBoundingClientRect().height || tools.offsetHeight || 0)
      : 0;
    const margin = toolbarH + 8;
    view.style.setProperty("--settings-scroll-margin", `${margin}px`);
    view.style.setProperty("--settings-toolbar-h", `${toolbarH}px`);
    return margin;
  }

  let settingsStickyMetricsHooked = false;
  let settingsBarLastScrollY = 0;
  let settingsScrollSpyBound = null;
  let settingsScrollSpyIgnoreUntil = 0;
  let settingsScrollSpyRaf = 0;

  function ensureSettingsStickyMetricsHook() {
    if (settingsStickyMetricsHooked) return;
    settingsStickyMetricsHooked = true;
    window.addEventListener("resize", () => {
      if ($("view-settings")?.classList.contains("hidden")) return;
      updateSettingsStickyMetrics();
      bindSettingsScrollSpy({ force: true });
    });
  }

  function settingsSpyIgnore(ms = 600) {
    settingsScrollSpyIgnoreUntil = Date.now() + ms;
  }

  /** Active group = last .sf-group whose top has crossed the sticky offset line. */
  function updateSettingsNavFromScroll() {
    if (Date.now() < settingsScrollSpyIgnoreUntil) return;
    const catalog = $("settingsCatalog");
    const view = $("view-settings");
    if (!catalog || !view || view.classList.contains("hidden")) return;
    const groups = [...catalog.querySelectorAll("details.sf-group[data-group]")];
    if (!groups.length) return;

    const scroller = settingsScrollParent(catalog);
    const scrollerTop = scroller.getBoundingClientRect
      ? scroller.getBoundingClientRect().top
      : 0;
    const pad = settingsIndexTopPad() + 10;
    let active = groups[0].dataset.group;
    for (const g of groups) {
      const relTop = g.getBoundingClientRect().top - scrollerTop;
      if (relTop - pad <= 2) active = g.dataset.group;
    }
    if (active && active !== state.settingsFocusGroup) {
      syncSettingsMiniNav(active);
      ensureSettingsMiniNavIndexVisible(active);
    } else if (active) {
      syncSettingsMiniNav(active);
    }
  }

  function scheduleSettingsNavFromScroll() {
    if (settingsScrollSpyRaf) return;
    settingsScrollSpyRaf = requestAnimationFrame(() => {
      settingsScrollSpyRaf = 0;
      updateSettingsNavFromScroll();
    });
  }

  function bindSettingsScrollSpy({ force = false } = {}) {
    const catalog = $("settingsCatalog");
    if (!catalog) return;
    const scroller = settingsScrollParent(catalog);
    if (!scroller) return;
    if (!force && settingsScrollSpyBound === scroller) return;

    if (settingsScrollSpyBound && settingsScrollSpyBound !== scroller) {
      settingsScrollSpyBound.removeEventListener("scroll", onSettingsScrollSpy);
    }
    settingsScrollSpyBound = scroller;
    settingsBarLastScrollY = scroller.scrollTop || 0;
    scroller.removeEventListener("scroll", onSettingsScrollSpy);
    scroller.addEventListener("scroll", onSettingsScrollSpy, { passive: true });
    afterLayout(() => updateSettingsNavFromScroll());
  }

  function onSettingsScrollSpy() {
    if ($("view-settings")?.classList.contains("hidden")) return;
    const sp = settingsScrollSpyBound;
    if (!sp) return;
    const y = sp.scrollTop || 0;
    const delta = y - settingsBarLastScrollY;
    if (y <= 8) {
      document.body.classList.remove("settings-topbar-away");
    } else if (delta > 6) {
      document.body.classList.add("settings-topbar-away");
    }
    settingsBarLastScrollY = y;
    scheduleSettingsNavFromScroll();
  }

  /** Find the element that actually scrolls for settings (view-settings or an ancestor). */
  function settingsScrollParent(fromEl) {
    const view = $("view-settings");
    let node = fromEl || view;
    while (node && node !== document.body) {
      const style = getComputedStyle(node);
      const oy = style.overflowY;
      if (
        (oy === "auto" || oy === "scroll" || oy === "overlay")
        && node.scrollHeight > node.clientHeight + 1
      ) {
        return node;
      }
      node = node.parentElement;
    }
    return view || document.scrollingElement || document.documentElement;
  }

  /** Mobile settings layout (≤900px): horizontal sticky mini-nav above the catalog. */
  function isSettingsMobileLayout() {
    try {
      return window.matchMedia("(max-width: 900px)").matches;
    } catch (_) {
      return (window.innerWidth || 0) <= 900;
    }
  }

  /**
   * Offset so the group *summary* lands at the start of the visible area.
   * Accounts for sticky toolbar + mini-nav.
   */
  function settingsIndexTopPad() {
    const margin = updateSettingsStickyMetrics();
    return margin || 16;
  }

  /**
   * Scroll a settings group into view under sticky toolbar + mini-nav.
   * Uses scroll-margin-top on .sf-group and scrollIntoView for reliable targeting.
   */
  function scrollSettingsIndexTo(groupEl, { smooth = true } = {}) {
    if (!groupEl) return;
    updateSettingsStickyMetrics();
    const reduceMotion = document.body.classList.contains("pref-reduce-motion");
    const behavior = smooth && !reduceMotion ? "smooth" : "auto";

    try {
      groupEl.scrollIntoView({ behavior, block: "start" });
      return;
    } catch (_) {}

    const anchor = groupEl.querySelector(":scope > .sf-group-summary") || groupEl;
    const scroller = settingsScrollParent(groupEl);
    const pad = settingsIndexTopPad();
    const y =
      anchor.getBoundingClientRect().top
      - scroller.getBoundingClientRect().top
      + scroller.scrollTop
      - pad;
    const maxTop = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
    const next = Math.min(maxTop, Math.max(0, Math.round(y)));
    try {
      scroller.scrollTo({ top: next, behavior });
    } catch (_) {
      scroller.scrollTop = next;
    }
  }

  /** Keep the active mini-nav chip in view — only scroll the rail, never the page. */
  function ensureSettingsMiniNavIndexVisible(groupId) {
    const nav = $("settingsMiniNav");
    const btn = nav?.querySelector(`.settings-mini-item[data-group="${CSS.escape(groupId)}"]`);
    if (!nav || !btn) return;
    const navRect = nav.getBoundingClientRect();
    const btnRect = btn.getBoundingClientRect();
    const pad = 12;
    if (!isSettingsMobileLayout()) {
      if (btnRect.top < navRect.top + pad || btnRect.bottom > navRect.bottom - pad) {
        const deltaY = (btnRect.top + btnRect.bottom) / 2 - (navRect.top + navRect.bottom) / 2;
        try {
          nav.scrollBy({ top: deltaY, behavior: "smooth" });
        } catch (_) {
          nav.scrollTop += deltaY;
        }
      }
      return;
    }
    // Mobile horizontal rail — center the active chip.
    if (btnRect.left < navRect.left + pad || btnRect.right > navRect.right - pad) {
      const deltaX = (btnRect.left + btnRect.right) / 2 - (navRect.left + navRect.right) / 2;
      try {
        nav.scrollBy({ left: deltaX, behavior: "smooth" });
      } catch (_) {
        nav.scrollLeft += deltaX;
      }
    }
  }

  function expandGroupPartitions(groupEl) {
    if (!groupEl) return;
    groupEl.querySelectorAll("details.sf-partition").forEach((p) => { p.open = true; });
  }

  function afterLayout(cb) {
    requestAnimationFrame(() => {
      requestAnimationFrame(cb);
    });
  }

  /** Expand & scroll to one Ava-settings group (all groups stay open). */
  function focusSettingsGroup(groupId, { scroll = false } = {}) {
    if (!groupId) return;
    const catalog = $("settingsCatalog");
    if (!catalog) return;
    const groups = [...catalog.querySelectorAll("details.sf-group[data-group]")];
    if (!groups.some((g) => g.dataset.group === groupId)) return;
    // Open the target group (don't close others — flat card layout).
    const focused = catalog.querySelector(`details.sf-group[data-group="${CSS.escape(groupId)}"]`);
    if (focused) focused.open = true;
    settingsSpyIgnore(scroll ? 800 : 0);
    syncSettingsMiniNav(groupId);
    if (!focused) return;

    if (!scroll) {
      expandGroupPartitions(focused);
      return;
    }

    ensureSettingsStickyMetricsHook();
    updateSettingsStickyMetrics();
    bindSettingsScrollSpy();
    ensureSettingsMiniNavIndexVisible(groupId);
    scrollSettingsIndexTo(focused, { smooth: true });

    afterLayout(() => {
      scrollSettingsIndexTo(focused, { smooth: false });
      window.setTimeout(() => {
        updateSettingsStickyMetrics();
        scrollSettingsIndexTo(focused, { smooth: false });
        ensureSettingsMiniNavIndexVisible(groupId);
        settingsSpyIgnore(200);
      }, 420);
    });
  }

  function settingsGroupLabel(id) {
    const key = `settingsGroup.${id}`;
    const translated = t(key);
    return translated === key ? id : translated;
  }

  function settingsGroupHint(id) {
    const key = `settingsGroupHint.${id}`;
    const translated = t(key);
    return translated === key ? "" : translated;
  }

  /**
   * Fields stored under `voice_satellite` but shown under the partition that matches
   * the in-app settings screens (browser / media player).
   * data-key stays `voice_satellite.<field>` so apply still writes the right store.
   */
  const VOICE_SATELLITE_FIELD_UI_HOME = {
    haRemoteUrl: "browser",
    haMediaPlayerEntity: "player",
    haMediaPlayerDuckEnabled: "player",
    haMediaPlayerDuckVolume: "player",
  };

  /**
   * Experimental-store fields remounted to match Ava **modules**
   * (each ServiceFeature / Environment / Diagnostic / Camera screen = one module).
   * data-key stays `experimental.<field>`.
   */
  const EXPERIMENTAL_FIELD_UI_HOME = {
    // 设备服务 → 主页界面
    syncDarkModeToHass: "__service_home",

    // 设备服务 → 屏幕与触控（每个入口是独立模块）
    screenPowerControlHaDisplayEnabled: "__service_screen_power",
    screenBrightnessEnabled: "__service_screen_brightness",
    forceOrientationEnabled: "__service_force_orientation",
    forceOrientationMode: "__service_force_orientation",

    // 设备服务 → 传感器（环境 / 接近 / 高级诊断 = 三个模块）
    environmentSensorEnabled: "__service_environment",
    sensorUpdateInterval: "__service_environment",
    environmentLightSensorEnabled: "__service_environment",
    environmentMagneticSensorEnabled: "__service_environment",

    proximitySensorEnabled: "__service_proximity",
    proximitySendToHass: "__service_proximity",
    proximityHassUpdateInterval: "__service_proximity",
    proximityWakeScreen: "__service_proximity",
    proximityAwayDelay: "__service_proximity",
    proximityAutoUnlock: "__service_proximity",

    diagnosticSensorEnabled: "__service_diagnostic",
    diagnosticWifiEnabled: "__service_diagnostic",
    diagnosticIpEnabled: "__service_diagnostic",
    diagnosticStorageEnabled: "__service_diagnostic",
    diagnosticMemoryEnabled: "__service_diagnostic",
    diagnosticUptimeEnabled: "__service_diagnostic",
    diagnosticKillAppEnabled: "__service_diagnostic",
    diagnosticRebootEnabled: "__service_diagnostic",
    diagnosticBatteryLevelEnabled: "__service_diagnostic",
    diagnosticBatteryVoltageEnabled: "__service_diagnostic",
    diagnosticChargingStatusEnabled: "__service_diagnostic",

    // 高阶功能 → 各功能模块
    cameraEnabled: "__advanced_camera",
    cameraMode: "__advanced_camera",
    cameraPosition: "__advanced_camera",
    cameraOrientation: "__advanced_camera",
    imageSize: "__advanced_camera",
    videoFps: "__advanced_camera",
    videoResolution: "__advanced_camera",
    personDetectionEnabled: "__advanced_camera",
    faceBoxEnabled: "__advanced_camera",

    intentLauncherEnabled: "__advanced_intent",
    intentLauncherHaDisplayEnabled: "__advanced_intent",

    clusterManagementEnabled: "__advanced_cluster",
    webConsoleEnabled: "__advanced_cluster",
    clusterAccessToken: "__advanced_cluster",

    // 语音配置 → 音频事件
    audioEventDetectionEnabled: "__voice_audio_event",
    audioEventMonitoredLabels: "__voice_audio_event",
    audioEventSensitivity: "__voice_audio_event",
    audioEventDisplaySeconds: "__voice_audio_event",
  };

  /** @deprecated — advanced modules remounted via EXPERIMENTAL_FIELD_UI_HOME */
  const EXPERIMENTAL_UI_SECTIONS = [];

  /**
   * Player-store fields remounted to match in-app modules.
   * data-key stays `player.<field>`.
   */
  const PLAYER_FIELD_UI_HOME = {
    enableDreamClock: "notification",
    enableDreamClockDisplay: "notification",
    enableScreensaver: "notification",
    enableScreensaverDisplay: "notification",
    screensaverDoubleTapToggleEnabled: "notification",
    screensaverWallpaperUrl: "notification",
    screensaverWallpaperRefreshSeconds: "notification",
    screensaverWallpaperDualPane: "notification",
    screensaverWallpaperDarkOverlayEnabled: "notification",
    enableWeatherOverlay: "notification",
    enableWeatherOverlayDisplay: "notification",
    haWeatherEntity: "notification",
    enableTimerStopButton: "quick_entity",

    enableContinuousConversation: "__voice_assist",
    enableQuestionMarkContinue: "__voice_assist",
    enableExitKeywordStop: "__voice_assist",
    exposeWhisperResponseEntity: "__voice_assist",
    whisperResponseVolume: "__voice_assist",
    enableHaSwitchOverlay: "__voice_assist",
    enableManualDismissButton: "__voice_assist",
    enableFloatingWindow: "__voice_assist",
    enableStreamingTtsSubtitles: "__voice_assist",
    enableWakeSound: "__voice_assist",
    enableStopSound: "__voice_assist",
    enableContinuousPromptSound: "__voice_assist",
    wakeSound: "__voice_assist",
    wakeSound2: "__voice_assist",
    stopSound: "__voice_assist",
    continuousPromptSound: "__voice_assist",
    timerFinishedSound: "__voice_assist",

    // 语音配置 → 语音反馈颜色（preset key 或 #RRGGBB）
    voiceWakeWord1AccentColor: "__voice_feedback_accent",
    voiceWakeWord2AccentColor: "__voice_feedback_accent",
    enableVoiceRippleEffect: "__voice_feedback_accent",
    enableVoiceEdgeGlow: "__voice_feedback_accent",
    voiceWakeWord1EdgeGlowLevelGain: "__voice_feedback_accent",
    voiceWakeWord2EdgeGlowLevelGain: "__voice_feedback_accent",
    voiceWakeWord1EdgeGlowSheer: "__voice_feedback_accent",
    voiceWakeWord2EdgeGlowSheer: "__voice_feedback_accent",
    voiceEdgeGlowLevelGain: "__voice_feedback_accent",

    enableMinimalLauncher: "__service_launcher",
    enableMinimalLauncherHaDisplay: "__service_launcher",
    minimalLauncherVisiblePackages: "__service_launcher",
    minimalLauncherIconPack: "__service_launcher",
    minimalLauncherIconShape: "__service_launcher",
    minimalLauncherShowDesktopLabels: "__service_launcher",
    minimalLauncherWallpaperUri: "__service_launcher",
    minimalLauncherWallpaperMode: "__service_launcher",
    minimalLauncherWallpaperScale: "__service_launcher",
    minimalLauncherWallpaperOffsetX: "__service_launcher",
    minimalLauncherWallpaperOffsetY: "__service_launcher",
    enableAutoRestart: "__service_auto_restart",
    enableCrashSelfHeal: "__service_auto_restart",
    startServiceOnAppOpen: "__service_auto_restart",
    startServiceOnAppOpenDelaySeconds: "__service_auto_restart",
  };

  /**
   * Sendspin partition field order — overrides JSON serialization order in the
   * fleet console form. serverUrl first (connection method is the most important
   * knob, especially for mDNS-bypass setups), then the master switch, identity,
   * playback tuning, and finally runtime/volume state. Fields not listed here
   * keep their natural JSON order, appended after the ordered ones.
   */
  const SENDSPIN_FIELD_ORDER = [
    "serverUrl",
    "enabled",
    "customDeviceName",
    "preferredFormat",
    "syncOffsetMs",
    "lowMemoryMode",
    "volumeFollowRule",
    "volume",
    "muted",
    "pairedDevices",
  ];

  /** Placeholders for text fields that benefit from a format hint. */
  const TEXT_PLACEHOLDERS = {
    serverUrl: "ws://192.168.1.100:8095/sendspin",
    customDeviceName: "",
  };

  /** Runtime / migrated / FAB coords — keep in JSON, hide from form. */
  const HIDDEN_SETTINGS_FIELDS = new Set([
    // Karaoke lyrics are always on in-app; the stored flag is legacy-only.
    "enableKaraokeLyrics",
    "enableDreamClockVisible",
    "enableScreensaverVisible",
    "enableWeatherOverlayVisible",
    "enableVoiceMessageOverlayVisible",
    "enableBrowserVisible",
    "playbackUiMigrated",
    // Sidebar show* fields are rendered as a compact reorderable card, not individual rows.
    "showVoiceMessage",
    "showBrowser",
    "showWeather",
    "showSimpleClock",
    "showDreamClock",
    "showQuickEntity",
    "showVinylCoverDisplay",
    "showHomeLock",
    "showCamera",
    "showMuteMicrophone",
    "showDarkMode",
    "showHome",
    "eqMiniFabNormX",
    "eqMiniFabNormY",
    "eqMiniFabX",
    "eqMiniFabY",
    "enableVinylCover",
    // Legacy one-way flag; use sendspin.volumeFollowRule instead.
    "syncDeviceVolumeWithHa",
    // Sendspin internals: autoConnect / advertiseAsPlayer are always-on (no UI);
    // lastPlayedServerId is runtime state; useDeviceVolume is legacy (always true).
    // serverUrl IS exposed so users can bypass mDNS by pointing Ava directly at
    // the Music Assistant WebSocket.
    "autoConnect",
    "advertiseAsPlayer",
    "lastPlayedServerId",
    "useDeviceVolume",
    // No in-app settings UI (orphaned DataStore flags).
    "displaySizeEnabled",
    "displaySizeScale",
    // Device-local Mass API client cert; HA guide chrome.
    "clientCertAlias",
    "clientCertPassword",
    "guideDismissed",
    "overlayDismissed",
    "esphomeIdentityExpanded",
  ]);

  /** Ordered sections inside the player partition (media + messages). */
  const PLAYER_UI_SECTIONS = [
    {
      id: "media",
      fields: [
        "enableHaVinylCover",
        "enableSendspinVinylCover",
        "enableEqMiniPlayer",
        "mediaOverlayStyle",
      ],
    },
    {
      id: "ha_player",
      fields: [
        "exposeEsphomeMediaPlayerEntity",
        "haMediaPlayerEntity",
        "haMediaPlayerDuckEnabled",
        "haMediaPlayerDuckVolume",
        "muted",
        "volume",
      ],
    },
    {
      id: "messages",
      fields: [
        "enableVoiceMessageOverlay",
        "enableVoiceMessageOverlayDisplay",
        "enableVoiceMessageReceive",
        "voiceMessageReceiveMode",
        "voiceMessageDisplayName",
        "voiceMessageDelayMinutes",
        "enableVoiceOverlayCall",
        "enableVoiceOverlayIntercom",
        "enableVoiceCallAnswerRequired",
        "voiceCallRingtone",
        "voiceCallVideoQuality",
        "enableScreenOff",
      ],
    },
  ];

  /** Sidebar reorderable items: itemKey → show* field. Order matches Ava's DEFAULT_SIDEBAR_ITEM_ORDER. */
  const SIDEBAR_ITEMS = [
    { key: "Home", field: "showHome" },
    { key: "DarkMode", field: "showDarkMode" },
    { key: "VoiceMessage", field: "showVoiceMessage" },
    { key: "Browser", field: "showBrowser" },
    { key: "Weather", field: "showWeather" },
    { key: "SimpleClock", field: "showSimpleClock" },
    { key: "DreamClock", field: "showDreamClock" },
    { key: "QuickEntity", field: "showQuickEntity" },
    { key: "VinylCoverDisplay", field: "showVinylCoverDisplay" },
    { key: "HomeLock", field: "showHomeLock" },
    { key: "Camera", field: "showCamera" },
    { key: "MuteMicrophone", field: "showMuteMicrophone" },
  ];

  function sidebarItemLabel(key) {
    const map = {
      VoiceMessage: t("field.showVoiceMessage"),
      Browser: t("field.showBrowser"),
      Weather: t("field.showWeather"),
      SimpleClock: t("field.showSimpleClock"),
      DreamClock: t("field.showDreamClock"),
      QuickEntity: t("field.showQuickEntity"),
      VinylCoverDisplay: t("field.showVinylCoverDisplay"),
      HomeLock: t("field.showHomeLock"),
      Camera: t("field.showCamera"),
      MuteMicrophone: t("field.showMuteMicrophone"),
      Home: t("field.showHome"),
      DarkMode: t("field.showDarkMode"),
    };
    return map[key] || key;
  }

  function sidebarOrderedItems(store) {
    const order = Array.isArray(store?.itemOrder) ? store.itemOrder : [];
    const known = SIDEBAR_ITEMS.map((it) => it.key);
    const filtered = order.filter((k) => known.includes(k));
    const missing = known.filter((k) => !filtered.includes(k));
    const full = filtered.concat(missing);
    return full
      .map((key) => {
        const item = SIDEBAR_ITEMS.find((it) => it.key === key);
        if (!item) return null;
        const val = store?.[item.field];
        if (val === undefined) return null;
        return { key, field: item.field, val };
      })
      .filter(Boolean);
  }

  function renderSidebarItemsCard(store) {
    const items = sidebarOrderedItems(store);
    if (!items.length) return "";
    const rows = items.map((it) => {
      const checked = it.val === true;
      return `
        <div class="sf-sidebar-item" draggable="true" data-item-key="${escapeHtml(it.key)}" data-field="${escapeHtml(it.field)}">
          <span class="sf-sidebar-grip" aria-hidden="true">⣿</span>
          <span class="sf-sidebar-label">${escapeHtml(sidebarItemLabel(it.key))}</span>
          <input type="checkbox" class="sf-switch" ${checked ? "checked" : ""} role="switch" aria-checked="${checked ? "true" : "false"}" />
          </label>
        </div>`;
    }).join("");
    return `
      <div class="sf-sidebar-items" data-partition="sidebar_items">
        ${rows}
      </div>`;
  }

  function bindSidebarItemsCard(cardEl) {
    if (!cardEl || cardEl.dataset.bound) return;
    cardEl.dataset.bound = "1";

    // Switch toggles
    cardEl.querySelectorAll(".sf-sidebar-item").forEach((row) => {
      const cb = row.querySelector('input[type="checkbox"]');
      if (!cb) return;
      cb.addEventListener("change", () => {
        const field = row.dataset.field;
        if (!field || !state.settings?.sidebar) return;
        state.settings.sidebar[field] = cb.checked;
        onSettingsValueChanged();
      });
    });

    // Drag-to-reorder
    let dragKey = null;
    cardEl.querySelectorAll(".sf-sidebar-item").forEach((row) => {
      row.addEventListener("dragstart", (e) => {
        dragKey = row.dataset.itemKey;
        row.classList.add("sf-dragging");
        e.dataTransfer.effectAllowed = "move";
        try { e.dataTransfer.setData("text/plain", dragKey); } catch (_) {}
      });
      row.addEventListener("dragend", () => {
        row.classList.remove("sf-dragging");
        cardEl.querySelectorAll(".sf-sidebar-item").forEach((r) => r.classList.remove("sf-drag-over"));
        dragKey = null;
      });
      row.addEventListener("dragover", (e) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
        if (!dragKey || dragKey === row.dataset.itemKey) return;
        row.classList.add("sf-drag-over");
      });
      row.addEventListener("dragleave", () => {
        row.classList.remove("sf-drag-over");
      });
      row.addEventListener("drop", (e) => {
        e.preventDefault();
        row.classList.remove("sf-drag-over");
        if (!dragKey || dragKey === row.dataset.itemKey) return;
        const rows = Array.from(cardEl.querySelectorAll(".sf-sidebar-item"));
        const fromIdx = rows.findIndex((r) => r.dataset.itemKey === dragKey);
        const toIdx = rows.findIndex((r) => r.dataset.itemKey === row.dataset.itemKey);
        if (fromIdx < 0 || toIdx < 0) return;
        const newOrder = rows.map((r) => r.dataset.itemKey);
        const [moved] = newOrder.splice(fromIdx, 1);
        newOrder.splice(toIdx, 0, moved);
        // Reorder DOM
        if (fromIdx < toIdx) row.parentNode.insertBefore(rows[fromIdx], row.nextSibling);
        else row.parentNode.insertBefore(rows[fromIdx], row);
        // Persist
        if (state.settings?.sidebar) {
          state.settings.sidebar.itemOrder = newOrder;
          onSettingsValueChanged();
        }
      });
    });
  }

  function isHiddenSettingsField(field) {
    if (HIDDEN_SETTINGS_FIELDS.has(field)) return true;
    // HA/runtime visibility mirrors — not user settings rows in-app.
    if (/^enable.+Visible$/.test(field)) return true;
    return false;
  }

  function playerFieldsForHome(home) {
    const player = state.settings?.player;
    if (!player || typeof player !== "object") return [];
    const keys = Object.keys(PLAYER_FIELD_UI_HOME).filter(
      (field) => PLAYER_FIELD_UI_HOME[field] === home && !isHiddenSettingsField(field),
    );
    return keys
      .filter((field) => player[field] !== undefined || isVoiceAccentField(field))
      .map((field) => ({
        field,
        val: player[field] !== undefined ? player[field] : "",
      }));
  }

  function experimentalFieldsForHome(home) {
    const exp = state.settings?.experimental;
    if (!exp || typeof exp !== "object") return [];
    return Object.keys(EXPERIMENTAL_FIELD_UI_HOME)
      .filter((field) => EXPERIMENTAL_FIELD_UI_HOME[field] === home && exp[field] !== undefined && !isHiddenSettingsField(field))
      .map((field) => ({ field, val: exp[field] }));
  }

  function experimentalHasUiHome(home) {
    return experimentalFieldsForHome(home).length > 0;
  }


  /** Join field rows with Ava SettingsDivider-style separators (one module card). */
  function joinModuleFieldRows(fieldHtmlList) {
    const list = (fieldHtmlList || []).filter(Boolean);
    if (!list.length) return "";
    return list
      .map((html, i) => (i ? `<div class="sf-row-divider" role="separator"></div>` : "") + html)
      .join("");
  }

  /** DaisyUI collapse partition — details/summary for JS open-state tracking. */
  function partitionShell(partitionKey, count, bodyHtml, { open = true } = {}) {
    return `
      <details class="sf-partition" data-partition="${escapeHtml(partitionKey)}" ${open ? "open" : ""}>
        <summary class="sf-partition-summary">
          <span class="sf-partition-title">${escapeHtml(partitionLabel(partitionKey))}</span>
        </summary>
        <div class="sf-partition-fields">${bodyHtml}</div>
      </details>`;
  }

  function wrapModuleCard(fieldHtmlList) {
    const body = joinModuleFieldRows(fieldHtmlList);
    return body ? `<div class="sf-module">${body}</div>` : "";
  }

  function renderFieldSection(sectionId, fieldHtmlList) {
    const list = fieldHtmlList.filter(Boolean);
    if (!list.length) return "";
    const titleKey = `settingsSection.${sectionId}`;
    const title = t(titleKey) !== titleKey ? t(titleKey) : humanizeKey(sectionId);
    return `
      <div class="sf-section" data-section="${escapeHtml(sectionId)}">
        <div class="sf-section-title">${escapeHtml(title)}</div>
        ${wrapModuleCard(list)}
      </div>`;
  }

  /** Microphone fields that belong on the separate Voiceprint card (mirrors in-app Voice Print screen). */
  const MICROPHONE_VOICEPRINT_FIELDS = new Set([
    "voicePrintEnabled",
    "voicePrintEnrollmentMode",
    "voicePrintManualUser0Samples",
    "voicePrintManualUser1Samples",
    "voicePrintManualWakeVerifyEnabled",
    "voicePrintUserNames",
  ]);

  function isVoicePrintSettingsField(field) {
    return MICROPHONE_VOICEPRINT_FIELDS.has(field) || /^voicePrint/.test(field);
  }

  function buildMicrophonePartitionBlocks(store, { open = true } = {}) {
    const micFields = [];
    const printFields = [];
    const seen = new Set();
    const pushRendered = (storagePart, field, val, bucket) => {
      const key = `${storagePart}.${field}`;
      if (seen.has(key) || val === undefined) return;
      if (isHiddenSettingsField(field)) return;
      seen.add(key);
      bucket.push(renderField(key, resolveFieldDef(key, val), val));
    };
    for (const [field, val] of Object.entries(store || {})) {
      pushRendered("microphone", field, val, isVoicePrintSettingsField(field) ? printFields : micFields);
    }
    // Fold voice_channel into the microphone card (same in-app screen family).
    const channel = state.settings?.voice_channel;
    if (channel && typeof channel === "object") {
      for (const [field, val] of Object.entries(channel)) {
        pushRendered("voice_channel", field, val, micFields);
      }
    }
    const blocks = [];
    if (micFields.length) {
      blocks.push(partitionShell("microphone", micFields.length, wrapModuleCard(micFields), { open }));
    }
    if (printFields.length) {
      blocks.push(partitionShell("voiceprint", printFields.length, wrapModuleCard(printFields), { open }));
    }
    return blocks;
  }

  /** Browser → two cards (HA integration | page & interaction), desktop side-by-side. */
  const BROWSER_HA_FIELDS = [
    "haRemoteUrl",
    "haRemoteUrlEnabled",
    "enableBrowserDisplay",
    "advancedControlEnabled",
    "syncBrowserUrlEnabled",
    "showScaleSliderInHa",
  ];

  /** Screensaver → two cards (content | behavior), desktop side-by-side. */
  const SCREENSAVER_CONTENT_FIELDS = [
    "enabled",
    "visible",
    "enableHaDisplay",
    "xiaomiWallpaperEnabled",
    "screensaverUrl",
    "screensaverUrlVisible",
  ];

  function wrapSplit2(cardsHtml) {
    const cards = (cardsHtml || []).filter(Boolean);
    if (!cards.length) return "";
    if (cards.length === 1) return cards[0];
    return `<div class="sf-split-2">${cards.join("")}</div>`;
  }

  function buildSplitPartitionCard(partitionKey, fieldHtmlList, { open = true } = {}) {
    const fields = (fieldHtmlList || []).filter(Boolean);
    if (!fields.length) return "";
    return partitionShell(partitionKey, fields.length, wrapModuleCard(fields), { open });
  }

  function buildBrowserPartitionBlocks(store, { open = true } = {}) {
    const seen = new Set();
    const ha = [];
    const page = [];
    const pushRendered = (storagePart, field, val, bucket) => {
      const key = `${storagePart}.${field}`;
      if (seen.has(key) || val === undefined) return;
      if (isHiddenSettingsField(field)) return;
      seen.add(key);
      bucket.push(renderField(key, resolveFieldDef(key, val), val));
    };

    const haSet = new Set(BROWSER_HA_FIELDS);
    for (const [field, val] of Object.entries(store || {})) {
      pushRendered("browser", field, val, haSet.has(field) ? ha : page);
    }
    const voice = state.settings?.voice_satellite;
    if (voice && typeof voice === "object") {
      for (const [field, home] of Object.entries(VOICE_SATELLITE_FIELD_UI_HOME)) {
        if (home !== "browser" || voice[field] === undefined) continue;
        pushRendered("voice_satellite", field, voice[field], haSet.has(field) ? ha : page);
      }
    }

    return wrapSplit2([
      buildSplitPartitionCard("browser_ha", ha, { open }),
      buildSplitPartitionCard("browser_page", page, { open }),
    ]);
  }

  function buildScreensaverPartitionBlocks(store, { open = true } = {}) {
    const seen = new Set();
    const content = [];
    const behavior = [];
    const contentSet = new Set(SCREENSAVER_CONTENT_FIELDS);
    const pushRendered = (field, val, bucket) => {
      const key = `screensaver.${field}`;
      if (seen.has(key) || val === undefined) return;
      if (isHiddenSettingsField(field)) return;
      seen.add(key);
      bucket.push(renderField(key, resolveFieldDef(key, val), val));
    };
    for (const [field, val] of Object.entries(store || {})) {
      pushRendered(field, val, contentSet.has(field) ? content : behavior);
    }
    return wrapSplit2([
      buildSplitPartitionCard("screensaver_content", content, { open }),
      buildSplitPartitionCard("screensaver_behavior", behavior, { open }),
    ]);
  }

  function buildPartitionBlock(part, store, { open = true } = {}) {
    if (part === "microphone") {
      return buildMicrophonePartitionBlocks(store, { open }).join("");
    }
    if (part === "browser") {
      return buildBrowserPartitionBlocks(store, { open });
    }
    if (part === "screensaver") {
      return buildScreensaverPartitionBlocks(store, { open });
    }
    const seen = new Set();
    const pushRendered = (storagePart, field, val, bucket) => {
      const key = `${storagePart}.${field}`;
      if (seen.has(key) || val === undefined) return;
      if (isHiddenSettingsField(field)) return;
      seen.add(key);
      bucket.push(renderField(key, resolveFieldDef(key, val), val));
    };

    // Player → sectioned media; scenes/voice/launcher remounted elsewhere.
    // Messages and ha_player are rendered as their own partition cards (separate collapses).
    if (part === "player") {
      const sectionHtml = [];
      const claimed = new Set();
      const standaloneBuckets = {};
      for (const section of PLAYER_UI_SECTIONS) {
        if (section.id === "messages" || section.id === "ha_player") {
          standaloneBuckets[section.id] = [];
          for (const field of section.fields) {
            if (PLAYER_FIELD_UI_HOME[field]) continue;
            if (field === "haMediaPlayerEntity" || field === "haMediaPlayerDuckEnabled" || field === "haMediaPlayerDuckVolume") {
              const voice = state.settings?.voice_satellite;
              if (voice && voice[field] !== undefined) {
                pushRendered("voice_satellite", field, voice[field], standaloneBuckets[section.id]);
                claimed.add(field);
                continue;
              }
            }
            if (store && store[field] !== undefined) {
              pushRendered("player", field, store[field], standaloneBuckets[section.id]);
              claimed.add(field);
            }
          }
          continue;
        }
        const bucket = [];
        for (const field of section.fields) {
          if (PLAYER_FIELD_UI_HOME[field]) continue;
          if (store && store[field] !== undefined) {
            pushRendered("player", field, store[field], bucket);
            claimed.add(field);
          }
        }
        sectionHtml.push(renderFieldSection(section.id, bucket));
      }
      // Leftover player fields that weren't remounted or sectioned.
      const other = [];
      for (const [field, val] of Object.entries(store || {})) {
        if (PLAYER_FIELD_UI_HOME[field]) continue;
        if (claimed.has(field)) continue;
        pushRendered("player", field, val, other);
      }
      sectionHtml.push(renderFieldSection("other", other));
      const body = sectionHtml.filter(Boolean).join("");
      const playerHtml = body
        ? partitionShell(part, (body.match(/data-key=/g) || []).length, `<div class="sf-partition-fields-stack">${body}</div>`, { open })
        : "";
      const standaloneHtml = ["ha_player", "messages"]
        .map((sid) => {
          const b = standaloneBuckets[sid] || [];
          const bBody = wrapModuleCard(b);
          return bBody
            ? partitionShell(sid, (bBody.match(/data-key=/g) || []).length, bBody, { open })
            : "";
        })
        .filter(Boolean)
        .join("");
      return playerHtml + standaloneHtml;
    }

    // Experimental leftovers only — camera / intent / cluster remounted as their own modules.
    if (part === "experimental") {
      const other = [];
      for (const [field, val] of Object.entries(store || {})) {
        if (EXPERIMENTAL_FIELD_UI_HOME[field]) continue;
        pushRendered("experimental", field, val, other);
      }
      if (!other.length) return "";
      return partitionShell(part, other.length, wrapModuleCard(other), { open });
    }

    const fields = [];
    const entries = Object.entries(store || {});
    if (part === "sendspin") {
      entries.sort((a, b) => {
        const ia = SENDSPIN_FIELD_ORDER.indexOf(a[0]);
        const ib = SENDSPIN_FIELD_ORDER.indexOf(b[0]);
        if (ia === -1 && ib === -1) return 0;
        if (ia === -1) return 1;
        if (ib === -1) return -1;
        return ia - ib;
      });
    }
    for (const [field, val] of entries) {
      if (part === "voice_satellite" && VOICE_SATELLITE_FIELD_UI_HOME[field]) continue;
      pushRendered(part, field, val, fields);
    }

    // Borrow remapped player fields into notification UI home.
    if (part === "notification") {
      for (const { field, val } of playerFieldsForHome("notification")) {
        pushRendered("player", field, val, fields);
      }
    }

    // Borrow remapped player fields into quick_entity UI home.
    if (part === "quick_entity") {
      for (const { field, val } of playerFieldsForHome("quick_entity")) {
        pushRendered("player", field, val, fields);
      }
    }

    // Sidebar: prepend compact reorderable items card before other fields.
    let sidebarItemsHtml = "";
    if (part === "sidebar") {
      sidebarItemsHtml = renderSidebarItemsCard(store);
    }

    if (!fields.length && !sidebarItemsHtml) return "";
    const fieldsHtml = fields.length ? wrapModuleCard(fields) : "";
    const body = sidebarItemsHtml + fieldsHtml;
    const count = (body.match(/data-key=/g) || []).length + (sidebarItemsHtml ? (sidebarItemsHtml.match(/data-item-key=/g) || []).length : 0);
    return partitionShell(part, count, body, { open });
  }

  /** One Ava module = one partition card (player fields remounted here). */
  function buildPlayerHomeBlock(home, partitionKey, { open = true } = {}) {
    const items = playerFieldsForHome(home);
    if (!items.length) return "";
    const fields = items.map(({ field, val }) =>
      renderField(`player.${field}`, resolveFieldDef(`player.${field}`, val), val),
    );
    return partitionShell(partitionKey, fields.length, wrapModuleCard(fields), { open });
  }

  /** One Ava module = one partition card (experimental fields remounted here). */
  function buildExperimentalHomeBlock(home, partitionKey, { open = true } = {}) {
    const items = experimentalFieldsForHome(home);
    if (!items.length) return "";
    const fields = items.map(({ field, val }) =>
      renderField(`experimental.${field}`, resolveFieldDef(`experimental.${field}`, val), val),
    );
    return partitionShell(partitionKey, fields.length, wrapModuleCard(fields), { open });
  }

  function playerHasUiHome(home) {
    return playerFieldsForHome(home).length > 0;
  }

  function voiceSatelliteHasUiHome(part) {
    const voice = state.settings?.voice_satellite;
    if (!voice || typeof voice !== "object") return false;
    return Object.entries(VOICE_SATELLITE_FIELD_UI_HOME).some(
      ([field, home]) => home === part && voice[field] !== undefined,
    );
  }

  // Same catalog URL as ModManager.STORE_URL — console reads it directly (no /v1/mods).
  const MOD_STORE_URL = "https://raw.githubusercontent.com/knoop7/ava-mods/main/store.json";
  const MOD_STORE_GITHUB_PROXY = "https://ghfast.top/";

  /** zh UI (or zh browser locale) → ghfast mirror; others hit GitHub directly. */
  function shouldProxyGithubStore() {
    try {
      if (String(getLang() || "").toLowerCase().startsWith("zh")) return true;
    } catch (_) {}
    try {
      const nav = String(navigator.language || navigator.userLanguage || "").toLowerCase();
      if (nav.startsWith("zh")) return true;
    } catch (_) {}
    return false;
  }

  /** Mirror ModManager.proxiedUrl — only wrap github / raw.githubusercontent hosts. */
  function proxiedModStoreUrl(url) {
    const raw = String(url || "").trim();
    if (!raw) return raw;
    if (raw.startsWith(MOD_STORE_GITHUB_PROXY)) return raw;
    if (!shouldProxyGithubStore()) return raw;
    // Strip cache-buster for host match; keep original (with query) when wrapping.
    const normalized = raw.toLowerCase();
    if (normalized.startsWith("https://raw.githubusercontent.com/") || normalized.startsWith("https://github.com/")) {
      return MOD_STORE_GITHUB_PROXY + raw;
    }
    if (normalized.startsWith("http://raw.githubusercontent.com/") || normalized.startsWith("http://github.com/")) {
      return MOD_STORE_GITHUB_PROXY + raw.replace(/^http:\/\//i, "https://");
    }
    return raw;
  }

  /** zh: proxy → direct. en/de/ru: direct only. */
  function modStoreFetchCandidates(url) {
    const raw = String(url || "").trim();
    if (!raw) return [];
    if (!shouldProxyGithubStore()) return [raw];
    const proxied = proxiedModStoreUrl(raw);
    if (proxied === raw) return [raw];
    return [proxied, raw];
  }

  function normalizeModStoreBaseUrl(baseUrl) {
    const s = String(baseUrl || "").trim();
    if (!s) return "";
    return s.endsWith("/") ? s : `${s}/`;
  }

  function joinModStoreAssetUrl(baseUrl, modPath, file) {
    const base = normalizeModStoreBaseUrl(baseUrl);
    const path = String(modPath || "");
    const name = String(file || "").replace(/^\/+/, "");
    return `${base}${path}${name}`;
  }

  function compareModVersions(a, b) {
    const pa = String(a || "").split(/[.+_-]/).map((x) => parseInt(x.replace(/\D/g, ""), 10) || 0);
    const pb = String(b || "").split(/[.+_-]/).map((x) => parseInt(x.replace(/\D/g, ""), 10) || 0);
    const n = Math.max(pa.length, pb.length);
    for (let i = 0; i < n; i++) {
      const d = (pa[i] || 0) - (pb[i] || 0);
      if (d) return d;
    }
    return 0;
  }

  async function fetchModStoreBytes(url) {
    const candidates = modStoreFetchCandidates(url);
    let lastErr = null;
    for (const candidate of candidates) {
      try {
        // Use raw window.fetch — no fleet token, no custom headers (Cache-Control /
        // Pragma are not CORS-safelisted and trigger OPTIONS → Failed to fetch).
        const res = await rawFetch(candidate, {
          method: "GET",
          mode: "cors",
          credentials: "omit",
          cache: "no-store",
        });
        if (!res.ok) {
          lastErr = new Error(`HTTP ${res.status}`);
          continue;
        }
        return new Uint8Array(await res.arrayBuffer());
      } catch (err) {
        lastErr = err;
      }
    }
    throw lastErr || new Error("fetch_failed");
  }

  async function fetchModStoreText(url) {
    const bytes = await fetchModStoreBytes(url);
    return new TextDecoder("utf-8").decode(bytes);
  }

  function bytesToBase64(bytes) {
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let binary = "";
    const chunk = 0x8000;
    for (let i = 0; i < u8.length; i += chunk) {
      binary += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  async function refreshOnlineModStore({ force = false } = {}) {
    const ms = state.modStore;
    if (!ms) return;
    if (ms.status === "loading") return;
    if (!force && ms.status !== "idle") return;
    ms.status = "loading";
    ms.error = "";
    try {
      const bust = `${MOD_STORE_URL}?ts=${Date.now()}`;
      const json = await fetchModStoreText(bust);
      const store = JSON.parse(json);
      const baseUrl = normalizeModStoreBaseUrl(store.baseUrl || "");
      if (!baseUrl) throw new Error("missing_baseUrl");
      const mods = Array.isArray(store.mods)
        ? store.mods
            .filter((m) => m && m.id && m.path)
            .map((m) => ({
              id: String(m.id),
              name: String(m.name || m.id),
              version: String(m.version || ""),
              author: String(m.author || ""),
              description: String(m.description || ""),
              detailDescription: String(m.detail_description || m.detailDescription || ""),
              path: String(m.path),
              jarHash: m.jar_hash || m.jarHash || null,
            }))
        : [];
      ms.baseUrl = baseUrl;
      ms.mods = mods;
      ms.status = "ok";
      ms.error = "";
      renderSettingsForm({ syncJson: false, preserveOpen: true });
    } catch (err) {
      console.warn("refreshOnlineModStore:", err);
      ms.status = "err";
      ms.error = err?.message || String(err);
      showFleetToast(t("modStoreLoadError"), { err: true, key: "modStoreLoadError" });
      renderSettingsForm({ syncJson: false, preserveOpen: true });
    }
  }

  function buildInstalledModRegistry(modsStore, extra) {
    const byId = new Map();
    for (const [id, mod] of Object.entries(modsStore || {})) {
      byId.set(id, {
        id,
        version: String(mod?.version || ""),
        enabled: mod?.enabled !== false,
        installedAt: Number(mod?.installedAt) || Date.now(),
      });
    }
    if (extra?.id) {
      const prev = byId.get(extra.id);
      byId.set(extra.id, {
        id: extra.id,
        version: String(extra.version || prev?.version || ""),
        enabled: prev ? prev.enabled : true,
        installedAt: prev?.installedAt || Date.now(),
      });
    }
    return {
      version: 1,
      mods: [...byId.values()].sort((a, b) => a.id.localeCompare(b.id)),
    };
  }

  async function downloadAndInstallStoreMod(modId) {
    const ms = state.modStore;
    const storeMod = (ms.mods || []).find((m) => m.id === modId);
    if (!storeMod) {
      showFleetToast(t("modStoreNotFound"), { err: true, key: "modStoreNotFound" });
      return;
    }
    if (ms.downloading[modId]) return;
    ms.downloading[modId] = true;
    renderSettingsForm({ syncJson: false, preserveOpen: true });
    try {
      let baseUrl = normalizeModStoreBaseUrl(ms.baseUrl);
      if (!baseUrl) {
        await refreshOnlineModStore({ force: true });
        baseUrl = normalizeModStoreBaseUrl(state.modStore.baseUrl);
      }
      if (!baseUrl) throw new Error("missing_baseUrl");

      showFleetToast(t("modStoreFetchingManifest"), { key: `modDl:${modId}` });
      const manifestUrl = `${joinModStoreAssetUrl(baseUrl, storeMod.path, "manifest.json")}?ts=${Date.now()}`;
      const manifestText = await fetchModStoreText(manifestUrl);
      const manifest = JSON.parse(manifestText);
      const libs = Array.isArray(manifest.libs) ? manifest.libs : [];
      const files = { "manifest.json": bytesToBase64(new TextEncoder().encode(manifestText)) };

      for (let i = 0; i < libs.length; i++) {
        const lib = String(libs[i] || "").replace(/^\/+/, "");
        if (!lib) continue;
        const shortName = lib.split("/").pop();
        showFleetToast(
          t("modStoreDownloadingLib")
            .replace("{i}", String(i + 1))
            .replace("{n}", String(libs.length))
            .replace("{name}", shortName),
          { key: `modDl:${modId}` },
        );
        const libUrl = `${joinModStoreAssetUrl(baseUrl, storeMod.path, lib)}?ts=${Date.now()}`;
        const bytes = await fetchModStoreBytes(libUrl);
        files[lib] = bytesToBase64(bytes);
      }

      const version = String(manifest.version || storeMod.version || "");
      const registry = buildInstalledModRegistry(state.settings?.mods || {}, {
        id: modId,
        version,
      });
      const payload = {
        format: "ava-backup",
        mods: {
          registry,
          packages: {
            [modId]: { files },
          },
        },
      };

      showFleetToast(t("modStoreInstalling"), { key: `modDl:${modId}` });
      const res = await fetch("/v1/settings/import", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json().catch(() => ({}));
      if (body && body.ok === false) throw new Error(body.error || body.message || "import_failed");

      await loadSettings();
      showFleetToast(t("modStoreInstallOk").replace("{name}", storeMod.name || modId), {
        key: `modDlOk:${modId}`,
      });
    } catch (err) {
      console.warn("downloadAndInstallStoreMod:", err);
      showFleetToast(
        t("modStoreInstallError").replace("{msg}", err?.message || String(err)),
        { err: true, key: `modDlErr:${modId}` },
      );
      renderSettingsForm({ syncJson: false, preserveOpen: true });
    } finally {
      delete state.modStore.downloading[modId];
      renderSettingsForm({ syncJson: false, preserveOpen: true });
    }
  }

  function bindModStoreActions(root) {
    root?.querySelectorAll("[data-mod-store-action]").forEach((btn) => {
      if (btn.dataset.bound === "1") return;
      btn.dataset.bound = "1";
      btn.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        const action = btn.dataset.modStoreAction;
        const modId = btn.dataset.modId;
        if ((action === "download" || action === "update") && modId) {
          void downloadAndInstallStoreMod(modId);
        }
      });
    });
  }

  function renderInstalledModCard(modId, mod) {
    const name = mod.name || modId;
    const version = mod.version || "";
    const enabled = !!mod.enabled;
    const badges = [
      enabled
        ? `<span class="sf-badge ok">${escapeHtml(t("modEnabled"))}</span>`
        : `<span class="sf-badge warn">${escapeHtml(t("modDisabled"))}</span>`,
      mod.hasUpdate ? `<span class="sf-badge info">${escapeHtml(t("modUpdate"))}</span>` : "",
      Number(mod.missingPermissions) > 0
        ? `<span class="sf-badge warn">${escapeHtml(t("modMissingPerm"))}</span>`
        : "",
    ].filter(Boolean).join("");
    const schema = Array.isArray(mod.schema) ? mod.schema : [];
    const config = mod.config && typeof mod.config === "object" ? mod.config : {};

    const enableField = renderField(
      `mods.${modId}.enabled`,
      { type: "bool", label: t("modEnabled") },
      enabled,
    );

    const configFields = schema.map((item) => {
      if (!item || !item.key) return "";
      const key = `mods.${modId}.config.${item.key}`;
      const raw = config[item.key];
      const defVal = item.defaultValue != null && item.defaultValue !== "" ? item.defaultValue : null;
      let val = raw != null && raw !== "" ? raw : defVal;
      const type = String(item.type || "text").toLowerCase();
      if (type === "switch" || type === "bool" || type === "boolean") {
        const on = String(val ?? "true").toLowerCase() === "true";
        return renderField(key, { type: "bool", label: item.label || item.key, description: item.description }, on);
      }
      if (type === "select" && Array.isArray(item.options) && item.options.length) {
        return renderField(
          key,
          {
            type: "select",
            label: item.label || item.key,
            description: item.description,
            options: item.options,
            optionLabels: Object.fromEntries(item.options.map((o) => [o, o])),
          },
          val != null ? String(val) : String(item.options[0] ?? ""),
        );
      }
      if (type === "number") {
        const n = Number(val);
        return renderField(
          key,
          {
            type: "number",
            label: item.label || item.key,
            description: item.description,
            min: item.min,
            max: item.max,
          },
          Number.isFinite(n) ? n : (Number(defVal) || 0),
        );
      }
      return renderField(
        key,
        { type: "text", label: item.label || item.key, description: item.description },
        val != null ? String(val) : "",
      );
    }).filter(Boolean).join("");

    const storeHit = (state.modStore.mods || []).find((m) => m.id === modId);
    const needsUpdate = !!(
      storeHit
      && compareModVersions(storeHit.version, version) > 0
    );
    const busy = !!state.modStore.downloading[modId];
    const updateBtn = needsUpdate
      ? `<button type="button" class="sf-mod-action" data-mod-store-action="update" data-mod-id="${escapeHtml(modId)}" title="${escapeHtml(t("modStoreUpdate"))}" aria-label="${escapeHtml(t("modStoreUpdate"))}" ${busy ? "disabled" : ""}>${MOD_UPDATE_ICON}</button>`
      : "";

    return `
      <details class="sf-mod-card sf-mod-installed" data-mod-id="${escapeHtml(modId)}">
        <summary class="sf-mod-summary">
          <span class="sf-mod-head">
            <span class="sf-mod-title-row">
              <span class="sf-mod-title">${escapeHtml(name)}</span>
              ${version ? `<span class="sf-mod-ver">v${escapeHtml(version)}</span>` : ""}
            </span>
            <span class="sf-mod-id">${escapeHtml(modId)}</span>
          </span>
          <span class="sf-mod-badges">${badges}${needsUpdate ? `<span class="sf-badge warn">${escapeHtml(t("modUpdate"))}</span>` : ""}</span>
        </summary>
        <div class="sf-mod-body">
          ${updateBtn ? `<div class="sf-mod-actions">${updateBtn}</div>` : ""}
          ${enableField}
          ${configFields || `<div class="sf-mod-empty">${escapeHtml(t("modNoConfig"))}</div>`}
        </div>
      </details>`;
  }

  /** Inline download icon (mirrors Ava app's Icons.Filled.Download). */
  const MOD_DOWNLOAD_ICON = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M5 21h14"/></svg>`;
  /** Inline refresh icon for update action. */
  const MOD_UPDATE_ICON = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/></svg>`;

  function renderOnlineModCard(storeMod, installed) {
    const modId = storeMod.id;
    const busy = !!state.modStore.downloading[modId];
    const local = installed?.[modId];
    const isInstalled = !!local;
    const needsUpdate = isInstalled && compareModVersions(storeMod.version, local.version || "") > 0;
    let actionLabel = t("modStoreDownload");
    let action = "download";
    let icon = MOD_DOWNLOAD_ICON;
    let statusClass = "info";
    let statusLabel = t("modStoreAvailable");
    if (busy) {
      actionLabel = t("modStoreBusy");
      statusLabel = t("modStoreBusy");
    } else if (needsUpdate) {
      actionLabel = t("modStoreUpdate");
      action = "update";
      icon = MOD_UPDATE_ICON;
      statusClass = "info";
      statusLabel = t("modUpdate");
    } else if (isInstalled) {
      actionLabel = t("modStoreInstalled");
      action = "";
      statusClass = "ok";
      statusLabel = t("modStoreInstalled");
    }
    const desc = storeMod.description || storeMod.detailDescription || "";

    return `
      <article class="sf-mod-card sf-mod-store-card${busy ?" is-busy" : ""}${isInstalled ? " is-installed" : ""}" data-mod-id="${escapeHtml(modId)}">
        <div class="sf-mod-top">
          <div class="sf-mod-head">
            <div class="sf-mod-title-row">
              <h4 class="sf-mod-title">${escapeHtml(storeMod.name || modId)}</h4>
              ${storeMod.version ? `<span class="sf-mod-ver">v${escapeHtml(storeMod.version)}</span>` : ""}
            </div>
          </div>
          ${action
            ? `<button type="button" class="sf-mod-action" data-mod-store-action="${escapeHtml(action)}" data-mod-id="${escapeHtml(modId)}" title="${escapeHtml(actionLabel)}" aria-label="${escapeHtml(actionLabel)}" ${busy ? "disabled" : ""}>${icon}</button>`
            : `<span class="sf-mod-done">${escapeHtml(actionLabel)}</span>`}
        </div>
        ${desc ? `<p class="sf-mod-desc">${escapeHtml(desc)}</p>` : ""}
      </article>`;
  }

  /** Mod Store UI — online catalog (direct URL) + installed enable/config. */
  function buildModsPartitionBlock(modsStore, { open = true } = {}) {
    const ids = Object.keys(modsStore || {}).sort((a, b) => {
      const na = (modsStore[a]?.name || a).toLowerCase();
      const nb = (modsStore[b]?.name || b).toLowerCase();
      return na.localeCompare(nb);
    });
    const installedCards = ids.length
      ? ids.map((id) => renderInstalledModCard(id, modsStore[id] || {})).join("")
      : `<div class="muted sf-mod-empty">${escapeHtml(t("noModules"))}</div>`;

    const online = state.modStore.mods || [];
    let onlineBody = "";
    if (state.modStore.status === "loading" && !online.length) {
      onlineBody = `<div class="muted sf-mod-empty">${escapeHtml(t("modStoreLoading"))}</div>`;
    } else if (state.modStore.status === "err" && !online.length) {
      onlineBody = `<div class="muted status-err sf-mod-empty">${escapeHtml(t("modStoreLoadError"))}</div>`;
    } else if (!online.length) {
      onlineBody = `<div class="muted sf-mod-empty">${escapeHtml(t("modStoreEmpty"))}</div>`;
    } else {
      onlineBody = online
        .slice()
        .sort((a, b) => String(a.name || a.id).localeCompare(String(b.name || b.id)))
        .map((m) => renderOnlineModCard(m, modsStore))
        .join("");
    }

    return `
      <div class="sf-mods-section">
        <div class="sf-mods-section-title">${escapeHtml(t("modStoreInstalledSection"))}</div>
        <div class="sf-mods-list sf-mods-installed">${installedCards}</div>
      </div>
      <div class="sf-mods-section">
        <div class="sf-mods-section-title">${escapeHtml(t("modStoreAvailableSection"))}</div>
        <div class="sf-mods-list sf-mods-store-grid">${onlineBody}</div>
      </div>`;
  }

  async function loadSettings() {
    try {
      const [settingsRes, schemaRes, haRes] = await Promise.all([
        fetch("/v1/settings", { cache: "no-store" }),
        fetch("/v1/settings/schema", { cache: "no-store" }),
        fetch("/v1/settings/ha", { cache: "no-store" }),
      ]);
      let schemaFromEndpoint = null;
      if (schemaRes.ok) {
        const data = await schemaRes.json();
        schemaFromEndpoint = data.schema || data;
      }
      if (settingsRes.ok) {
        const data = await settingsRes.json();
        state.settings = data.settings || data;
        state.schema = data.schema || schemaFromEndpoint;
        state.meta = data.meta || null;
      } else if (schemaFromEndpoint) {
        state.schema = schemaFromEndpoint;
      }
      if (haRes.ok) {
        const data = await haRes.json();
        state.haSettings = data.switches || data;
      }
      markSettingsDirty(false);
      renderSettingsForm({ syncJson: true });
      renderHaSwitches();
      populateTransferPeers();
    } catch (err) {
      console.warn("loadSettings:", err);
      const cat = $("settingsCatalog");
      if (cat) cat.innerHTML = `<div class="muted">${escapeHtml(t("settingsLoadError"))}</div>`;
    }
  }

  function renderSettingsForm({ syncJson = true, preserveOpen = false } = {}) {
    if (!state.settings) return;
    const catalog = $("settingsCatalog");
    if (!catalog) return;
    const openState = preserveOpen ? captureCatalogOpenState() : null;
    const blocks = [];
    const renderedGroups = [];
    const nested = Array.isArray(state.schema?.partitions) || isNestedSettingsShape(state.settings);
    const used = new Set();

    if (nested) {
      const partitionKeys = Array.isArray(state.schema?.partitions)
        ? state.schema.partitions.map((p) => p.key).filter(Boolean)
        : Object.keys(state.settings);

      for (const group of SETTINGS_GROUPS) {
        const partsHtml = [];
        for (const part of group.partitions) {
          const store = state.settings[part];
          const hasStore = store && typeof store === "object" && !Array.isArray(store);
          const hasRemapped = voiceSatelliteHasUiHome(part)
            || (part === "notification" && playerHasUiHome("notification"))
            || (part === "quick_entity" && playerHasUiHome("quick_entity"));
          if (!partitionKeys.includes(part) && !hasRemapped) continue;
          if (!hasStore && !hasRemapped) continue;
          used.add(part);
          if (part === "mods") {
            partsHtml.push(buildModsPartitionBlock(store || {}, { open: true }));
          } else if (part === "microphone") {
            partsHtml.push(...buildMicrophonePartitionBlocks(store || {}, { open: true }));
          } else {
            partsHtml.push(buildPartitionBlock(part, store || {}, { open: true }));
          }
        }
        // Remounts matching Ava Voice / Device Controls / Advanced screens.
        if (group.id === "voice") {
          partsHtml.push(buildPlayerHomeBlock("__voice_assist", "voice_assist", { open: true }));
          partsHtml.push(buildPlayerHomeBlock("__voice_feedback_accent", "voice_feedback_accent", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__voice_audio_event", "voice_audio_event", { open: true }));
        }
        if (group.id === "service") {
          // Match Ava ServiceFeature / Environment / Diagnostic modules (one card each).
          partsHtml.push(buildExperimentalHomeBlock("__service_home", "service_home", { open: true }));
          partsHtml.push(buildPlayerHomeBlock("__service_launcher", "service_launcher", { open: true }));
          partsHtml.push(buildPlayerHomeBlock("__service_auto_restart", "service_auto_restart", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__service_screen_power", "service_screen_power", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__service_screen_brightness", "service_screen_brightness", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__service_force_orientation", "service_force_orientation", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__service_environment", "service_environment", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__service_proximity", "service_proximity", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__service_diagnostic", "service_diagnostic", { open: true }));
        }
        if (group.id === "advanced") {
          // Match Ava Experimental hub: camera / intent / cluster each a module.
          partsHtml.push(buildExperimentalHomeBlock("__advanced_camera", "advanced_camera", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__advanced_intent", "advanced_intent", { open: true }));
          partsHtml.push(buildExperimentalHomeBlock("__advanced_cluster", "advanced_cluster", { open: true }));
          const expStore = state.settings?.experimental;
          if (expStore && typeof expStore === "object") {
            used.add("experimental");
            partsHtml.push(buildPartitionBlock("experimental", expStore, { open: true }));
          }
        }
        if (!partsHtml.filter(Boolean).length) continue;
        renderedGroups.push(group.id);
        const hint = settingsGroupHint(group.id);
        const partCount = partsHtml.filter(Boolean).length;
        blocks.push(`
          <details class="sf-group" data-group="${escapeHtml(group.id)}" open>
            <summary class="sf-group-summary">
              <span class="sf-group-title">${escapeHtml(settingsGroupLabel(group.id))}</span>
              <span class="sf-group-meta">${partCount}</span>
            </summary>
            <div class="sf-group-body">${partsHtml.join("")}</div>
          </details>`);
      }
      // Leftover / orphan partitions are intentionally not shown (no "其他分区" dump).
    } else if (state.schema && typeof state.schema === "object" && !Array.isArray(state.schema.partitions)) {
      const fields = [];
      for (const [key, def] of Object.entries(state.schema)) {
        if (key === "partitions" || key === "haPublished" || key === "fields") continue;
        if (!def || typeof def !== "object" || !def.type) continue;
        if (state.settings[key] === undefined) continue;
        fields.push(renderField(key, def, state.settings[key]));
      }
      blocks.push(`<div class="settings-form">${fields.join("") || `<div class="muted">—</div>`}</div>`);
    } else {
      const fields = [];
      for (const [key, val] of Object.entries(state.settings)) {
        if (val === undefined) continue;
        fields.push(renderField(key, resolveFieldDef(key, val), val));
      }
      blocks.push(`<div class="settings-form">${fields.join("") || `<div class="muted">—</div>`}</div>`);
    }

    catalog.innerHTML = blocks.length ? blocks.join("") : `<div class="muted">—</div>`;
    buildSettingsMiniNav(renderedGroups);
    if (preserveOpen && openState?.size) {
      applyCatalogOpenState(openState);
    }
    // Prefer previous focus if still present; never let details toggle events steal it
    // (setting open=true on every group fires toggle in document order → last wins).
    const focusId = renderedGroups.includes(state.settingsFocusGroup)
      ? state.settingsFocusGroup
      : (renderedGroups[0] || null);
    syncSettingsMiniNav(focusId);
    catalog.querySelectorAll("[data-key]").forEach(bindFieldChange);
    catalog.querySelectorAll(".sf-sidebar-items").forEach(bindSidebarItemsCard);
    bindModStoreActions(catalog);
    if (syncJson) pushSettingsToJsonEditor({ force: true });
    enhanceSelects(catalog);
    ensureSettingsStickyMetricsHook();
    bindSettingsScrollSpy({ force: true });
    afterLayout(() => {
      updateSettingsStickyMetrics();
      updateSettingsNavFromScroll();
    });
    if (catalog.querySelector(".sf-mods-store-grid")) {
      void refreshOnlineModStore({ force: false });
    }
  }

  function renderField(key, def, val) {
    const name = fieldNameFromKey(key);
    const label = escapeHtml(fieldLabel(name, def, key));
    const descText = fieldDescription(name, def, key);
    const desc = descText ? `<div class="sf-desc">${escapeHtml(descText)}</div>` : "";
    const id = `sf-${escapeHtml(String(key).replace(/\./g, "-"))}`;
    const valueTypeAttr = def.valueType === "number" ? ' data-value-type="number"' : "";

    let inner = "";
    if (def.type === "bool") {
      const checked = val ? "checked" : "";
      inner = `
        <div class="sf-field-main">
          <div class="sf-field-copy">
            <label class="sf-field-label" for="${id}">${label}</label>
            ${desc}
          </div>
          <input type="checkbox" class="sf-switch" id="${id}" ${checked} role="switch" aria-checked="${val ? "true" : "false"}" aria-label="${label}" />
        </div>`;
    } else if (def.type === "number") {
      const min = def.min != null ? `min="${def.min}"` : "";
      const max = def.max != null ? `max="${def.max}"` : "";
      inner = `
        <label class="sf-field-label">${label}</label>
        ${desc}
        <input type="number" class="" value="${escapeHtml(String(val ?? ""))}" ${min} ${max} />`;
    } else if (def.type === "voiceAccent") {
      const raw = String(val ?? "").trim();
      const isHex = /^#[0-9A-Fa-f]{6}$/i.test(raw);
      const selectVal = isHex ? "__custom__" : raw;
      const displayHex = resolveVoiceAccentDisplayHex(name, raw);
      const hexText = isHex ? `#${raw.slice(1).toUpperCase()}` : "";
      const defaultHex = voiceAccentDefaultHex(name);
      const opts = [
        `<option value=""${selectVal === "" ? " selected" : ""}>${escapeHtml(t("voiceAccentDefault"))} (${defaultHex})</option>`,
        ...VOICE_ACCENT_RAINBOW.map(([k, hex]) => {
          const text = `${optionLabel("voiceAccent", k, null)} (${hex})`;
          return `<option value="${escapeHtml(k)}"${selectVal === k ? " selected" : ""}>${escapeHtml(text)}</option>`;
        }),
        `<option value="__custom__"${selectVal === "__custom__" ? " selected" : ""}>${escapeHtml(t("voiceAccentCustom"))}</option>`,
      ].join("");
      inner = `
        <label class="sf-field-label" for="${id}">${label}</label>
        ${desc}
        <div class="sf-accent" data-accent-field>
          <input type="color" class="sf-accent-picker" value="${escapeHtml(displayHex)}" aria-label="${escapeHtml(t("voiceAccentPicker"))}" />
          <select class="sf-accent-select" id="${id}">${opts}</select>
          <input type="text" class="sf-accent-hex" value="${escapeHtml(hexText)}" placeholder="#RRGGBB" maxlength="7" spellcheck="false" ${isHex ? "" : "hidden"} />
        </div>`;
    } else if (def.type === "select" && Array.isArray(def.options)) {
      const labels = def.optionLabels || {};
      const current = val == null ? "" : String(val);
      const values = def.options.map((o) => String(o));
      if (current && !values.includes(current)) values.unshift(current);
      const opts = values.map((v) => {
        const text = optionLabel(name, v, labels);
        const selected = current === v ? " selected" : "";
        return `<option value="${escapeHtml(v)}"${selected}>${escapeHtml(text)}</option>`;
      }).join("");
      inner = `
        <label class="sf-field-label">${label}</label>
        ${desc}
        <select class="">${opts}</select>`;
    } else if (def.type === "json" || isComplexSetting(val)) {
      const pretty = JSON.stringify(val ?? null, null, 2);
      const rows = Math.min(14, Math.max(3, String(pretty).split("\n").length + 1));
      return `
        <div class="sf-field sf-field-json" data-key="${escapeHtml(key)}" data-value-type="json">
          <label class="sf-field-label">${label}</label>
          ${desc}
          <textarea class="sf-json" id="${id}" rows="${rows}" spellcheck="false">${escapeHtml(pretty)}</textarea>
        </div>`;
    } else {
      const placeholder = TEXT_PLACEHOLDERS[name] || "";
      const placeholderAttr = placeholder ? ` placeholder="${escapeHtml(placeholder)}"` : "";
      inner = `
        <label class="sf-field-label">${label}</label>
        ${desc}
        <input type="text" class="" value="${escapeHtml(String(val ?? ""))}"${placeholderAttr} />`;
    }

    return `
      <div class="sf-field" data-key="${escapeHtml(key)}"${valueTypeAttr}>
        ${inner}
      </div>`;
  }

  function scheduleAutoSave() {
    clearTimeout(state.autoSaveTimer);
    state.autoSaveTimer = setTimeout(() => {
      saveSettings({ auto: true });
    }, 700);
  }

  /** Set `a.b.c` on a nested object, creating intermediate objects as needed. */
  function setNestedSetting(root, path, value) {
    const parts = String(path || "").split(".").filter(Boolean);
    if (!parts.length || !root || typeof root !== "object") return;
    let cur = root;
    for (let i = 0; i < parts.length - 1; i += 1) {
      const p = parts[i];
      if (!cur[p] || typeof cur[p] !== "object" || Array.isArray(cur[p])) {
        cur[p] = {};
      }
      cur = cur[p];
    }
    const leaf = parts[parts.length - 1];
    // Mod config values are always strings on device.
    if (parts[0] === "mods" && parts.includes("config") && parts[parts.length - 2] === "config") {
      cur[leaf] = value === true || value === false ? String(value) : String(value ?? "");
    } else {
      cur[leaf] = value;
    }
  }

  function onSettingsValueChanged() {
    markSettingsDirty(true);
    pushSettingsToJsonEditor();
    scheduleAutoSave();
  }

  function bindFieldChange(fieldEl) {
    const key = fieldEl.dataset.key;
    const valueType = fieldEl.dataset.valueType;
    const name = fieldNameFromKey(key);
    const checkbox = fieldEl.querySelector('input[type="checkbox"]');
    const accentRoot = fieldEl.querySelector("[data-accent-field]");
    const input = accentRoot
      ? null
      : fieldEl.querySelector('input[type="text"], input[type="number"], select');
    const jsonArea = fieldEl.querySelector("textarea.sf-json");

    const writeValue = (value) => {
      if (!state.settings) return;
      setNestedSetting(state.settings, key, value);
      onSettingsValueChanged();
    };

    if (accentRoot) {
      const select = accentRoot.querySelector(".sf-accent-select");
      const hexInput = accentRoot.querySelector(".sf-accent-hex");
      const picker = accentRoot.querySelector(".sf-accent-picker");
      const syncUi = (stored) => {
        const raw = String(stored ?? "").trim();
        const isHex = /^#[0-9A-Fa-f]{6}$/i.test(raw);
        if (select) select.value = isHex ? "__custom__" : raw;
        if (hexInput) {
          hexInput.hidden = !isHex;
          if (isHex) hexInput.value = `#${raw.slice(1).toUpperCase()}`;
        }
        if (picker) picker.value = resolveVoiceAccentDisplayHex(name, raw);
      };
      const commit = (next) => {
        writeValue(next);
        syncUi(next);
      };
      if (select) {
        select.addEventListener("change", () => {
          const v = select.value;
          if (v === "__custom__") {
            const current = normalizeVoiceAccentHex(hexInput?.value)
              || resolveVoiceAccentDisplayHex(name, "");
            if (hexInput) {
              hexInput.hidden = false;
              hexInput.value = current;
              hexInput.focus();
            }
            commit(current);
            return;
          }
          commit(v);
        });
      }
      if (hexInput) {
        const applyHex = () => {
          const hex = normalizeVoiceAccentHex(hexInput.value);
          if (!hex) return;
          hexInput.value = hex;
          commit(hex);
        };
        hexInput.addEventListener("change", applyHex);
        hexInput.addEventListener("keydown", (ev) => {
          if (ev.key === "Enter") {
            ev.preventDefault();
            applyHex();
          }
        });
      }
      if (picker) {
        picker.addEventListener("input", () => {
          const hex = normalizeVoiceAccentHex(picker.value);
          if (!hex) return;
          commit(hex);
        });
      }
      return;
    }

    const readInputValue = () => {
      if (input.type === "number" || valueType === "number") {
        const n = Number(input.value);
        return Number.isFinite(n) ? n : input.value;
      }
      return input.value;
    };

    if (checkbox) {
      checkbox.addEventListener("change", () => {
        writeValue(checkbox.checked);
        checkbox.setAttribute("aria-checked", checkbox.checked ? "true" : "false");
      });
    }
    if (jsonArea) {
      jsonArea.addEventListener("input", () => {
        try {
          writeValue(JSON.parse(jsonArea.value));
          jsonArea.classList.remove("is-invalid");
        } catch (_) {
          jsonArea.classList.add("is-invalid");
          markSettingsDirty(true);
          setJsonSyncStatus("error");
        }
      });
      jsonArea.addEventListener("blur", () => {
        try {
          const parsed = JSON.parse(jsonArea.value);
          writeValue(parsed);
          jsonArea.value = JSON.stringify(parsed, null, 2);
          jsonArea.classList.remove("is-invalid");
        } catch (_) {
          jsonArea.classList.add("is-invalid");
        }
      });
    }
    if (input) {
      const evt = input.tagName === "SELECT" ? "change" : "input";
      input.addEventListener(evt, () => writeValue(readInputValue()));
      if (evt === "input") {
        input.addEventListener("change", () => writeValue(readInputValue()));
      }
    }
  }

  function renderHaSwitches() {
    const el = $("settingsHaList");
    const switches = Array.isArray(state.haSettings) ? state.haSettings : [];
    if (!switches.length) {
      el.innerHTML = `<div class="muted">${escapeHtml(t("haNoSwitches"))}</div>`;
      return;
    }
    el.innerHTML = `
      <table class="ha-table">
        <thead><tr>
          <th>${escapeHtml(t("haKey"))}</th>
          <th>${escapeHtml(t("haState"))}</th>
        </tr></thead>
        <tbody>${switches.map((sw) => {
          const key = sw.key || sw.path || sw.field || "—";
          const enabled = sw.enabled != null ? !!sw.enabled : !!sw.state;
          // Protocol uses `labelKey` (i18n) — translate it; fall back to raw label/key.
          const labelText = sw.label
            || (sw.labelKey ? (t(sw.labelKey) !== sw.labelKey ? t(sw.labelKey) : humanizeKey(fieldNameFromKey(key))) : humanizeKey(fieldNameFromKey(key)));
          return `
          <tr>
            <td>${escapeHtml(labelText)}</td>
            <td>${enabled
              ? `<span class="badge ok">${escapeHtml(t("on"))}</span>`
              : `<span class="badge neutral">${escapeHtml(t("off"))}</span>`
            }</td>
          </tr>`;
        }).join("")}</tbody>
      </table>
    `;
  }

  async function saveSettings({ auto = false } = {}) {
    if (state.autoSaving) return;
    if (!commitJsonEditorIfNeeded()) {
      showFleetToast(t("invalidJson"), { err: true, key: "invalidJson" });
      return;
    }
    if (!state.settings) return;
    state.autoSaving = true;
    try {
      const res = await fetch("/v1/settings/apply", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ settings: state.settings }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      markSettingsDirty(false);
      pushSettingsToJsonEditor({ force: !state.jsonEditing });
      showFleetToast(auto ? t("settingsAutoSaved") : t("settingsSaved"), {
        err: false,
        key: auto ? "autosave-ok" : "save-ok",
      });
    } catch (err) {
      showFleetToast(t("settingsSaveError"), { err: true, key: "save-err" });
      console.warn(err);
    } finally {
      state.autoSaving = false;
    }
  }

  // ── Export / Import / Template ────────────────────

  function downloadJsonFile(filename, data) {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  async function exportSettings() {
    try {
      const res = await fetch("/v1/settings/export", { cache: "no-store" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      downloadJsonFile(`ava-backup-${new Date().toISOString().slice(0, 10)}.json`, data);
      flash($("settingsStatus"), t("settingsExportSuccess"), false);
    } catch (err) {
      console.warn(err);
      flash($("settingsStatus"), t("settingsLoadError"), true);
    }
  }

  function exportSettingsTemplate() {
    if (!commitJsonEditorIfNeeded()) {
      flash($("settingsStatus"), t("invalidJson"), true);
      return;
    }
    const payload = {
      format: "ava-settings-template",
      version: 1,
      exportedAt: new Date().toISOString(),
      settings: state.settings || {},
    };
    downloadJsonFile(`ava-settings-template-${new Date().toISOString().slice(0, 10)}.json`, payload);
    flash($("settingsStatus"), t("settingsTemplateSuccess"), false);
  }

  async function copySettingsJson() {
    if (!commitJsonEditorIfNeeded()) {
      flash($("settingsStatus"), t("invalidJson"), true);
      return;
    }
    const text = stringifySettings();
    try {
      await navigator.clipboard.writeText(text);
      flash($("settingsStatus"), t("settingsCopyOk"), false);
    } catch (_) {
      const editor = $("jsonEditor");
      if (editor) {
        editor.focus();
        editor.select();
      }
      flash($("settingsStatus"), t("settingsCopyFallback"), false);
    }
  }

  function openImportModal() {
    const modal = $("importModal");
    if (!modal) return;
    modal.classList.remove("hidden");
    $("importPasteArea").value = "";
    $("importFileName").textContent = "";
    $("importStatus").textContent = "";
    animateModalIn(modal);
  }

  function closeImportModal() {
    const modal = $("importModal");
    if (!modal || modal.classList.contains("hidden")) return;
    const finish = () => {
      modal.classList.add("hidden");
      motionClearInline(modal);
      motionClearInline(modalPanel(modal));
    };
    animateModalOut(modal).then(finish).catch(finish);
  }

  function setAdbAddStatus(text, { err = false } = {}) {
    const el = $("adbAddStatus");
    if (!el) return;
    if (!text) {
      el.hidden = true;
      el.textContent = "";
      return;
    }
    el.hidden = false;
    el.textContent = text;
    el.classList.toggle("err", !!err);
  }

  function showAdbAddPanel(which) {
    const main = $("adbAddPanelMain");
    const pair = $("adbAddPanelPair");
    if (!main || !pair) return;
    const toPair = which === "pair";
    main.classList.toggle("hidden", toPair);
    pair.classList.toggle("hidden", !toPair);
    const back = $("adbPairBackBtn");
    const title = $("adbAddTitle");
    const sub = $("adbAddSub");
    back?.classList.toggle("hidden", !toPair);
    if (title) title.textContent = toPair ? t("adbPairSection") : t("adbAddTitle");
    if (sub) sub.textContent = toPair ? t("adbPairDesc") : t("adbAddSub");
  }

  /** Normalize connect target: bare IP / trailing ":" → :5555. */
  function normalizeAdbConnectHost(raw) {
    let h = String(raw || "").trim().replace(/：/g, ":");
    if (!h) return "";
    if (!h.includes(":")) return `${h}:5555`;
    if (/:\s*$/.test(h)) return `${h.replace(/:\s*$/, "")}:5555`;
    return h;
  }

  function openAdbAddModal() {
    const modal = $("adbAddModal");
    if (!modal) {
      showFleetToast(t("adbAddTitle"), { err: true });
      return;
    }
    setAdbAddStatus("");
    showAdbAddPanel("main");
    modal.classList.remove("hidden");
    // Ensure visible even if a parent leftover had display issues.
    modal.style.display = "flex";
    animateModalIn(modal);
    fetch("/v1/adb/start", { method: "POST" }).catch(() => {});
    void refreshAdbConnectedList();
  }

  function closeAdbAddModal() {
    const modal = $("adbAddModal");
    if (!modal || modal.classList.contains("hidden")) return;
    const finish = () => {
      modal.classList.add("hidden");
      modal.style.display = "";
      motionClearInline(modal);
      motionClearInline(modalPanel(modal));
      showAdbAddPanel("main");
    };
    animateModalOut(modal).then(finish).catch(finish);
  }

  function isAdbSelfDevice(d, localModel) {
    const serial = String(d?.serial || "").trim();
    if (!serial) return true;
    if (/^emulator-\d+$/i.test(serial)) return true;
    const model = String(d?.model || d?.product || "").replace(/_/g, " ").trim();
    const local = String(localModel || state.status?.model || "").replace(/_/g, " ").trim();
    const network = serial.includes(".") && /:\d+$/.test(serial);
    if (!network && local && model && model.toLowerCase() === local.toLowerCase()) return true;
    if (network) {
      const host = serial.split(":").slice(0, -1).join(":");
      const localIp = String(state.status?.ip || "").trim();
      if ((host === "127.0.0.1" || host === "localhost") && (!model || !local || model.toLowerCase() === local.toLowerCase())) {
        return true;
      }
      if (localIp && host === localIp && (!model || !local || model.toLowerCase() === local.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  async function refreshAdbConnectedList() {
    const el = $("adbConnectedList");
    const countEl = $("adbConnectedCount");
    if (!el) return;
    el.textContent = t("adbRefreshing") || "…";
    if (countEl) countEl.textContent = "0";
    try {
      await fetch("/v1/adb/start", { method: "POST" }).catch(() => {});
      const res = await fetch("/v1/adb/devices", { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      const raw = Array.isArray(j.devices) ? j.devices : [];
      const localModel = state.status?.model || "";
      let selfList = Array.isArray(j.selfDevices) ? j.selfDevices.slice() : [];
      if (!selfList.length) selfList = raw.filter((d) => isAdbSelfDevice(d, localModel));
      const peers = raw.filter((d) => !isAdbSelfDevice(d, localModel));
      const rows = [
        ...selfList.map((d) => ({ ...d, self: true })),
        ...peers.map((d) => ({ ...d, self: false })),
      ];
      if (countEl) countEl.textContent = String(rows.length);
      if (!rows.length) {
        el.className = "adb-connected-list muted";
        el.textContent = t("adbConnectedEmpty");
        return;
      }
      el.className = "adb-connected-list";
      el.innerHTML = rows.map((d) => {
        const serial = d.serial || "";
        const model = d.model || d.product || serial;
        const state = d.state || "";
        const stateClass = state === "device" ? "ok" : "warn";
        const actions = d.self
          ? `<span class="dev-tag dev-tag-local">${escapeHtml(t("tagLocal"))}</span>`
          : `<button type="button" class="btn" data-adb-forget="${escapeHtml(serial)}">${escapeHtml(t("adbForgetBtn"))}</button>`;
        return `<div class="adb-connected-row">
          <div class="adb-connected-meta">
            <div class="adb-connected-name">${escapeHtml(model)}</div>
            <div class="adb-connected-serial mono muted">${escapeHtml(serial)}</div>
            <div class="adb-connected-state"><span class="badge ${stateClass}">${escapeHtml(state || "—")}</span></div>
          </div>
          <div class="row-actions">${actions}</div>
        </div>`;
      }).join("");
      el.querySelectorAll("[data-adb-forget]").forEach((btn) => {
        btn.addEventListener("click", () => {
          void adbForget(btn.getAttribute("data-adb-forget"));
        });
      });
    } catch (e) {
      el.className = "adb-connected-list muted";
      el.textContent = String(e);
    }
  }

    async function adbForget(serial, opts = {}) {
    const s = (serial || "").trim();
    if (!s) return;
    if (opts.confirm !== false && !window.confirm(t("adbForgetConfirm"))) return;
    try {
      const res = await fetch("/v1/adb/forget", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ serial: s, hostPort: s }),
      });
      const raw = await res.text();
      let j = {};
      try { j = JSON.parse(raw); } catch (_) {}
      // Installed APK may lack /v1/adb/forget (plain "Not Found").
      if (res.status === 404 || (!j.ok && /not\s*found/i.test(raw || ""))) {
        const fallback = await fetch("/v1/adb/disconnect", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ serial: s, hostPort: s }),
        });
        const raw2 = await fallback.text();
        let j2 = {};
        try { j2 = JSON.parse(raw2); } catch (_) {}
        if (j2.ok) {
          showFleetToast(t("adbForgetOk"), { key: "adb-forget-ok" });
          await refreshAdbConnectedList();
          return;
        }
        showFleetToast(t("adbForgetNeedApk"), { err: true, key: "adb-forget-apk" });
        setAdbAddStatus(
          [t("adbForgetNeedApk"), j2.stderr, j2.error, raw2].filter(Boolean).join("\n"),
          { err: true },
        );
        return;
      }
      if (j.ok) {
        showFleetToast(t("adbForgetOk"), { key: "adb-forget-ok" });
        await refreshAdbConnectedList();
        try { if (typeof refresh === "function") await refresh(); } catch (_) {}
      } else {
        showFleetToast(t("adbForgetFail"), { err: true, key: "adb-forget-fail" });
        setAdbAddStatus([j.stderr, j.error, raw].filter(Boolean).join("\n") || t("adbForgetFail"), { err: true });
      }
    } catch (e) {
      showFleetToast(t("adbForgetFail"), { err: true, key: "adb-forget-fail" });
      setAdbAddStatus(String(e), { err: true });
    }
  }

  async function adbPair() {
    const hostPort = ($("adbPairHost")?.value || "").trim().replace(/：/g, ":");
    const code = ($("adbPairCode")?.value || "").trim();
    if (!hostPort.includes(":")) {
      showFleetToast(t("adbNeedHostPort"), { err: true, key: "adb-need-hp" });
      return;
    }
    if (!code) {
      showFleetToast(t("adbNeedCode"), { err: true, key: "adb-need-code" });
      return;
    }
    setAdbAddStatus("…");
    try {
      const res = await fetch("/v1/adb/pair", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ hostPort, code }),
      });
      const j = await res.json().catch(() => ({}));
      const detail = [j.stdout, j.stderr, j.error].filter(Boolean).join("\n");
      if (j.ok) {
        showFleetToast(t("adbPairOk"), { key: "adb-pair-ok" });
        setAdbAddStatus(detail || t("adbPairOk"));
        const ip = hostPort.split(":")[0];
        if (ip && $("adbConnectHost")) {
          $("adbConnectHost").value = `${ip}:5555`;
        }
        showAdbAddPanel("main");
        $("adbConnectHost")?.focus();
      } else {
        showFleetToast(t("adbPairFail"), { err: true, key: "adb-pair-fail" });
        setAdbAddStatus(detail || t("adbPairFail"), { err: true });
      }
    } catch (e) {
      showFleetToast(t("adbPairFail"), { err: true, key: "adb-pair-fail" });
      setAdbAddStatus(String(e), { err: true });
    }
  }

  async function adbConnect() {
    const hostPort = normalizeAdbConnectHost($("adbConnectHost")?.value);
    if (!hostPort) {
      showFleetToast(t("adbNeedHostPort"), { err: true, key: "adb-need-hp" });
      return;
    }
    if ($("adbConnectHost")) $("adbConnectHost").value = hostPort;
    setAdbAddStatus("…");
    try {
      const res = await fetch("/v1/adb/connect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ hostPort }),
      });
      const j = await res.json().catch(() => ({}));
      const detail = [j.stdout, j.stderr, j.error].filter(Boolean).join("\n");
      if (j.error === "self_connect" || /self_connect/i.test(String(j.error || ""))) {
        showFleetToast(t("adbSelfConnect"), { err: true, key: "adb-self" });
        setAdbAddStatus(t("adbSelfConnect"), { err: true });
        return;
      }
      if (j.ok) {
        showFleetToast(t("adbConnectOk"), { key: "adb-conn-ok" });
        setAdbAddStatus(detail || t("adbConnectOk"));
        await refreshAdbConnectedList();
        // Peer may land a beat after connect / daemon settle.
        const listed = adbConnectedHasSerial(hostPort);
        if (!listed) {
          await new Promise((r) => setTimeout(r, 500));
          await refreshAdbConnectedList();
        }
        // Push into wall / device list immediately + kick a one-shot ADB wall capture.
        await refreshAdbFleetDevices(true);
        fetch(`/v1/adb/screen?serial=${encodeURIComponent(hostPort)}&maxWidth=480&quality=35&force=1`, {
          cache: "no-store",
        }).catch(() => {});
        try {
          if (typeof refresh === "function") await refresh();
        } catch (_) {}
        if (state.view === "overview") startWallThumbLoop(true);
      } else {
        showFleetToast(t("adbConnectFail"), { err: true, key: "adb-conn-fail" });
        setAdbAddStatus(detail || t("adbConnectFail"), { err: true });
      }
    } catch (e) {
      showFleetToast(t("adbConnectFail"), { err: true, key: "adb-conn-fail" });
      setAdbAddStatus(String(e), { err: true });
    }
  }

  function adbConnectedHasSerial(serial) {
    const key = String(serial || "").trim();
    if (!key) return false;
    const el = $("adbConnectedList");
    if (!el) return false;
    return Array.from(el.querySelectorAll(".adb-connected-serial")).some(
      (n) => String(n.textContent || "").trim() === key,
    );
  }

  async function adbRefreshDevices() {
    setAdbAddStatus("…");
    try {
      await refreshAdbConnectedList();
      const res = await fetch("/v1/adb/devices", { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      const list = Array.isArray(j.devices) ? j.devices : [];
      const lines = list.map((d) => `${d.serial}\t${d.state}\t${d.model || ""}`.trim());
      setAdbAddStatus(lines.length ? lines.join("\n") : (j.error || t("adbConnectedEmpty")));
    } catch (e) {
      setAdbAddStatus(String(e), { err: true });
    }
  }

  function unwrapImportedSettings(json) {
    if (!json || typeof json !== "object") return null;
    if (json.format === "ava-backup" || json.format === "ava-settings-template") {
      return json.settings && typeof json.settings === "object" ? json : null;
    }
    if (json.settings && typeof json.settings === "object" && !Array.isArray(json.settings)) {
      return { settings: json.settings };
    }
    if (isNestedSettingsShape(json)) {
      return { settings: json };
    }
    return null;
  }

  async function applyImport() {
    const statusEl = $("importStatus");
    let json = null;

    const fileInput = $("importFileInput");
    if (fileInput.files && fileInput.files.length > 0) {
      try {
        const text = await fileInput.files[0].text();
        json = JSON.parse(text);
      } catch (e) {
        statusEl.textContent = t("invalidJson");
        statusEl.className = "muted status-err";
        return;
      }
    } else {
      const paste = $("importPasteArea").value.trim();
      if (!paste) return;
      try {
        json = JSON.parse(paste);
      } catch (e) {
        statusEl.textContent = t("invalidJson");
        statusEl.className = "muted status-err";
        return;
      }
    }

    try {
      if (json?.format === "ava-backup") {
        const res = await fetch("/v1/settings/import", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(json),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        await loadSettings();
      } else {
        const unwrapped = unwrapImportedSettings(json);
        if (!unwrapped) throw new Error("unsupported_import_shape");
        const res = await fetch("/v1/settings/apply", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ settings: unwrapped.settings }),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        state.settings = unwrapped.settings;
        markSettingsDirty(false);
        renderSettingsForm({ syncJson: true });
        await loadSettings();
      }
      statusEl.textContent = t("settingsImportSuccess");
      statusEl.className = "muted status-ok";
      setTimeout(closeImportModal, 1200);
    } catch (err) {
      statusEl.textContent = t("settingsImportError");
      statusEl.className = "muted status-err";
      console.warn(err);
    }
  }

  // ── JSON editor (live bidirectional sync) ─────────

  function commitJsonEditorIfNeeded() {
    const editor = $("jsonEditor");
    if (!editor || !state.jsonEditing) return true;
    try {
      const parsed = JSON.parse(editor.value);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("bad_root");
      state.settings = parsed;
      state.jsonEditing = false;
      renderSettingsForm({ syncJson: false, preserveOpen: true });
      setJsonSyncStatus(state.settingsDirty ? "dirty" : "synced");
      return true;
    } catch (_) {
      setJsonSyncStatus("error");
      return false;
    }
  }

  function applyJsonFromEditor({ reformat = false } = {}) {
    const editor = $("jsonEditor");
    if (!editor) return false;
    let parsed;
    try {
      parsed = JSON.parse(editor.value);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("bad_root");
    } catch (_) {
      setJsonSyncStatus("error");
      return false;
    }
    state.settings = parsed;
    markSettingsDirty(true);
    state.jsonEditing = false;
    renderSettingsForm({ syncJson: false, preserveOpen: true });
    if (reformat) {
      state.jsonSuppress = true;
      editor.value = stringifySettings();
      state.jsonSuppress = false;
    }
    setJsonSyncStatus("dirty");
    scheduleAutoSave();
    return true;
  }

  function onJsonEditorInput() {
    if (state.jsonSuppress) return;
    state.jsonEditing = true;
    setJsonSyncStatus("editing");
    markSettingsDirty(true);
    clearTimeout(state.jsonSyncTimer);
    state.jsonSyncTimer = setTimeout(() => {
      applyJsonFromEditor();
    }, 450);
  }

  function onJsonEditorBlur() {
    clearTimeout(state.jsonSyncTimer);
    if (!state.jsonEditing) return;
    applyJsonFromEditor({ reformat: true });
  }

  function formatJsonEditor() {
    if (!applyJsonFromEditor({ reformat: true })) {
      flash($("settingsStatus"), t("invalidJson"), true);
    }
  }

  function reloadJsonFromForm() {
    state.jsonEditing = false;
    clearTimeout(state.jsonSyncTimer);
    pushSettingsToJsonEditor({ force: true });
  }

  // ── Transfer ──────────────────────────────────────

  function openTransferModal() {
    const modal = $("transferModal");
    if (!modal) return;
    modal.classList.remove("hidden");
    populateTransferPeers();
  }

  function closeTransferModal() {
    const modal = $("transferModal");
    if (modal) modal.classList.add("hidden");
  }

  function toggleTransferPanel() {
    const modal = $("transferModal");
    if (!modal) return;
    if (modal.classList.contains("hidden")) openTransferModal();
    else closeTransferModal();
  }

  function populateTransferPeers() {
    const sel = $("transferPeerSelect");
    if (!sel) return;
    // Settings sync needs advertised Ava HTTP agent only.
    const peers = fleetDevices(state.status).filter((d) => !d.local && d.host && hasAdvertisedAgent(d));
    sel.innerHTML = peers.length
      ? peers.map((d) => {
          const port = deviceAgentPort(d);
          return `<option value="${escapeHtml(d.host)}:${port}">${escapeHtml(d.name || d.id)} (${escapeHtml(d.host)})</option>`;
        }).join("")
      : `<option value="">${escapeHtml(t("settingsNoPeers"))}</option>`;

    // Bind once — avoid stacking listeners on every refresh
    if (!state.transferPeersBound) {
      sel.onchange = () => {
        const v = sel.value;
        if (v && v.includes(":")) {
          const [h, p] = v.split(":");
          $("transferHost").value = h;
          $("transferPort").value = p;
        }
      };
      state.transferPeersBound = true;
    }

    if (peers.length && sel.value) {
      const [h, p] = sel.value.split(":");
      $("transferHost").value = h || "";
      $("transferPort").value = p || String(deviceAgentPort(peers[0]) || "");
    }
    enhanceSelect(sel);
  }

  function getTransferTarget() {
    const host = $("transferHost").value.trim();
    const port = parseInt($("transferPort").value.trim() || "0", 10);
    return { host, port };
  }

  async function probePeer() {
    const { host, port } = getTransferTarget();
    const statusEl = $("transferStatus");
    if (!host || !(port > 0)) {
      if (statusEl) {
        statusEl.textContent = t("settingsNoPeers");
        statusEl.className = "muted status-err";
      }
      return;
    }
    statusEl.textContent = "…";
    state.peerProbed = false;
    try {
      const res = await fetch("/v1/cluster/probe", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ host, port }),
      });
      const data = await res.json();
      if (data.ok && data.hello) {
        state.peerProbed = true;
        const name = data.statusSummary?.deviceName || data.deviceName || data.hello?.deviceName || host;
        statusEl.textContent = `${t("settingsTransferProbeOk")} — ${name}`;
        statusEl.className = "muted status-ok";
      } else {
        statusEl.textContent = t("settingsTransferProbeFail");
        statusEl.className = "muted status-err";
      }
    } catch (err) {
      statusEl.textContent = t("settingsTransferProbeFail");
      statusEl.className = "muted status-err";
      console.warn(err);
    }
    $("transferPullBtn").disabled = !state.peerProbed;
    $("transferPushBtn").disabled = !state.peerProbed;
  }

  async function pullSettings() {
    const { host, port } = getTransferTarget();
    const statusEl = $("transferStatus");
    if (!host || !(port > 0)) {
      statusEl.textContent = t("settingsTransferError");
      statusEl.className = "muted status-err";
      return;
    }
    statusEl.textContent = "…";
    try {
      const res = await fetch("/v1/cluster/pull-settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ host, port }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      const backup = data.backup || data;
      state.pendingBackup = backup;
      $("transferReviewJson").value = JSON.stringify(backup, null, 2);
      $("transferReviewArea").classList.remove("hidden");
      statusEl.textContent = t("settingsTransferPullSuccess");
      statusEl.className = "muted status-ok";
    } catch (err) {
      statusEl.textContent = t("settingsTransferError");
      statusEl.className = "muted status-err";
      console.warn(err);
    }
  }

  async function pushSettings() {
    const { host, port } = getTransferTarget();
    const wantDownload = !!$("transferBackupChk")?.checked;
    const statusEl = $("transferStatus");
    if (!host || !(port > 0)) {
      statusEl.textContent = t("settingsTransferError");
      statusEl.className = "muted status-err";
      return;
    }
    statusEl.textContent = "…";
    try {
      const exportRes = await fetch("/v1/settings/export", { cache: "no-store" });
      if (!exportRes.ok) throw new Error(`HTTP ${exportRes.status}`);
      const backup = await exportRes.json();
      if (wantDownload) {
        downloadJsonFile(`ava-backup-${new Date().toISOString().slice(0, 10)}.json`, backup);
      }
      const res = await fetch("/v1/cluster/push-settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ host, port, backup }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      statusEl.textContent = t("settingsTransferPushSuccess");
      statusEl.className = "muted status-ok";
    } catch (err) {
      statusEl.textContent = t("settingsTransferError");
      statusEl.className = "muted status-err";
      console.warn(err);
    }
  }

  async function applyPulledSettings() {
    try {
      const parsed = JSON.parse($("transferReviewJson").value);
      const res = await fetch("/v1/settings/import", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(parsed),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      await loadSettings();
      state.pendingBackup = null;
      $("transferReviewArea").classList.add("hidden");
      flash($("transferStatus"), t("settingsApplied"), false);
    } catch (e) {
      flash($("transferStatus"), e?.message === "Unexpected token" || e instanceof SyntaxError
        ? t("invalidJson")
        : t("settingsTransferError"), true);
      console.warn(e);
    }
  }

  function cancelPulledReview() {
    $("transferReviewArea").classList.add("hidden");
    state.pendingBackup = null;
  }

  // ── Device logs (ADB logcat) ──────────────────────

  function logsSerialValue() {
    return ($("logsDeviceSelect")?.value || state.logsSerial || "").trim();
  }

  function startLogsPolling() {
    stopLogsPolling();
    if (!state.logsLive) return;
    state.logsTimer = setInterval(() => {
      if (state.view === "activity" && state.logsLive) void refreshLogs();
    }, LOGS_POLL_MS);
  }

  function stopLogsPolling() {
    if (state.logsTimer) {
      clearInterval(state.logsTimer);
      state.logsTimer = null;
    }
  }

  async function refreshLogs() {
    if (state.logsBusy) return;
    const now = Date.now();
    if (now - state.logsLastFetchAt < LOGS_CLIENT_THROTTLE_MS) return;
    state.logsBusy = true;
    state.logsLastFetchAt = now;

    try {
      await refreshAdbDeviceOptions();
      const serial = logsSerialValue();
      if (!serial) {
        state.logs = [];
        renderLogs();
        return;
      }
      const q = new URLSearchParams({ serial, limit: String(LOGS_LIMIT) });
      const res = await fetch(`/v1/adb/logs?${q}`, { cache: "no-store" });
      const j = await res.json().catch(() => ({}));
      if (!res.ok || j.ok === false) throw new Error(j.error || `HTTP ${res.status}`);
      const lines = Array.isArray(j.lines) ? j.lines : [];
      const label = $("logsDeviceSelect")?.selectedOptions?.[0]?.textContent || serial;
      state.logs = lines.map((line) => ({
        deviceId: serial,
        deviceName: label,
        level: line.level || "?",
        tag: line.tag || "",
        time: line.time || "",
        message: line.message || line.raw || "",
        raw: line.raw || line.message || "",
      }));
      renderLogs();
    } catch (e) {
      showFleetToast(String(e?.message || e), { err: true, key: "logs" });
    } finally {
      state.logsBusy = false;
    }
  }

  function renderLogs() {
    const box = $("logConsole");
    if (!box) return;
    if (!state.logs.length) {
      box.innerHTML = `<div class="log-empty">${escapeHtml(t("logsEmpty"))}</div>`;
      return;
    }
    box.innerHTML = state.logs.map((line) => {
      const lvl = String(line.level || "?").slice(0, 1).toUpperCase();
      const rawTime = String(line.time || "");
      const timeShort = rawTime.includes(" ") ? rawTime.split(/\s+/).slice(1).join(" ") : rawTime;
      const time = escapeHtml(timeShort || rawTime);
      const timeFull = escapeHtml(rawTime);
      const tag = escapeHtml(String(line.tag || ""));
      const msg = escapeHtml(line.message || line.raw || "");
      return `<div class="log-line lvl-${escapeHtml(lvl)}"><span class="log-time" title="${timeFull}">${time}</span><span class="log-lvl">${escapeHtml(lvl)}</span><span class="log-tag" title="${tag}">${tag}</span><span class="log-msg">${msg}</span></div>`;
    }).join("");
    box.scrollTop = box.scrollHeight;
  }

  function clearLogs() {
    state.logs = [];
    renderLogs();
  }

  // ── Abnormal ends (crash / stall records kept on the device) ──

  /**
   * `logcat` above dies with the process, so a crash erases its own trace. These
   * records outlive it. They come from the same `/v1/logs` the console already
   * knows, asked with `limit=1` so the logcat part costs nothing, and only when
   * this view is opened or Refresh is pressed — never on the Live timer.
   *
   * A device whose "Show and export logs" switch is off returns an empty array;
   * that is a device-side decision the console does not override.
   */
  async function refreshIncidents({ force = false } = {}) {
    if (state.incidentsBusy) return;
    const now = Date.now();
    if (!force && now - state.incidentsLastFetchAt < INCIDENTS_THROTTLE_MS) return;
    state.incidentsBusy = true;
    state.incidentsLastFetchAt = now;

    const targets = incidentTargets();
    const rows = [];
    let offCount = 0;
    let failCount = 0;
    try {
      for (const target of targets) {
        try {
          const payload = await fetchIncidentPayload(target);
          if (!payload) {
            failCount += 1;
            continue;
          }
          if (payload.incidentsEnabled === false) offCount += 1;
          const list = Array.isArray(payload.incidents) ? payload.incidents : [];
          const name = payload.deviceName || target.name || target.host || "";
          for (const it of list) {
            rows.push({
              deviceName: name,
              ts: Number(it?.ts || 0),
              kind: String(it?.kind || ""),
              reason: String(it?.reason || ""),
              detail: String(it?.detail || ""),
              stuckMs: Number(it?.stuckMs || 0),
              uptimeMs: Number(it?.uptimeMs || 0),
              version: String(it?.version || ""),
            });
          }
        } catch (_) {
          failCount += 1;
        }
      }
      rows.sort((a, b) => b.ts - a.ts);
      state.incidents = rows.slice(0, INCIDENTS_MAX_ROWS);
      state.incidentsTally = { targets: targets.length, offCount, failCount };
      renderIncidents();
    } finally {
      state.incidentsBusy = false;
    }
  }

  /** This device first, then advertised Ava peers — capped, so a big wall stays cheap. */
  function incidentTargets() {
    const targets = [{ local: true, name: state.status?.deviceName || "" }];
    for (const d of fleetDevices(state.status)) {
      if (d.local || !d.host || !hasAdvertisedAgent(d)) continue;
      const port = deviceAgentPort(d);
      if (!(port > 0)) continue;
      targets.push({ local: false, host: d.host, port, name: d.name || d.id || d.host });
      if (targets.length >= INCIDENTS_MAX_DEVICES) break;
    }
    return targets;
  }

  async function fetchIncidentPayload(target) {
    if (target.local) {
      const res = await fetch("/v1/logs?limit=1", { cache: "no-store" });
      const j = await res.json().catch(() => null);
      return res.ok ? j : null;
    }
    const res = await fetch("/v1/cluster/pull-logs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ host: target.host, port: target.port, limit: 1 }),
    });
    const j = await res.json().catch(() => null);
    return res.ok && j?.ok !== false ? j : null;
  }

  /** Built at render time, not at fetch time, so a language switch relabels it. */
  function incidentsNoteText() {
    const tally = state.incidentsTally;
    if (!tally) return "";
    const { targets, offCount, failCount } = tally;
    if (targets > 0 && offCount >= targets) return t("incidentsOff");
    const parts = [];
    if (offCount > 0) parts.push(t("incidentsOffSome").replace("{n}", String(offCount)));
    if (failCount > 0) parts.push(t("logsPeerFail"));
    return parts.join(" · ");
  }

  function renderIncidents() {
    const box = $("incidentsList");
    const note = $("incidentsNote");
    if (note) note.textContent = incidentsNoteText();
    if (!box) return;
    if (!state.incidents.length) {
      box.innerHTML = `<div class="incidents-empty">${escapeHtml(t("incidentsEmpty"))}</div>`;
      return;
    }
    box.innerHTML = state.incidents.map((it, idx) => {
      const kind = escapeHtml(incidentKindLabel(it.kind));
      const when = escapeHtml(formatIncidentTime(it.ts));
      const dev = escapeHtml(it.deviceName || "");
      const stuck = it.stuckMs > 0
        ? `<span class="incident-stuck">${escapeHtml(t("incidentsStuck").replace("{ms}", String(it.stuckMs)))}</span>`
        : "";
      const reason = escapeHtml(it.reason || "");
      return `<button type="button" class="incident-row kind-${escapeHtml(incidentTone(it.kind))}" data-incident="${idx}">`
        + `<span class="incident-when">${when}</span>`
        + `<span class="incident-kind">${kind}</span>`
        + `<span class="incident-reason" title="${reason}">${reason}</span>`
        + stuck
        + `<span class="incident-dev" title="${dev}">${dev}</span>`
        + `</button>`;
    }).join("");
  }

  function incidentTone(kind) {
    if (kind === "crash_java" || kind === "crash_native") return "crash";
    if (kind === "stall_restart" || kind === "stall_only") return "stall";
    if (kind === "renderer_crash") return "renderer";
    return "plain";
  }

  function incidentKindLabel(kind) {
    const key = {
      crash_java: "incidentsKindCrash",
      crash_native: "incidentsKindNative",
      stall_restart: "incidentsKindStallRestart",
      stall_only: "incidentsKindStall",
      renderer_crash: "incidentsKindRenderer",
      restart_user: "incidentsKindRestart",
      exit_user: "incidentsKindExit",
      kill_remote: "incidentsKindKill",
    }[kind];
    return key ? t(key) : (kind || t("incidentsKindOther"));
  }

  function formatIncidentTime(ts) {
    if (!(ts > 0)) return "—";
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, "0");
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  function openIncidentModal(idx) {
    const it = state.incidents[idx];
    const modal = $("incidentModal");
    if (!it || !modal) return;
    $("incidentModalTitle").textContent = incidentKindLabel(it.kind);
    $("incidentModalDevice").textContent = it.deviceName || "";
    const rows = [
      [t("incidentsMetaTime"), formatIncidentTime(it.ts)],
      [t("incidentsMetaReason"), it.reason || "—"],
      [t("incidentsMetaUptime"), it.uptimeMs > 0 ? `${Math.round(it.uptimeMs / 1000)}s` : "—"],
      [t("incidentsMetaVersion"), it.version || "—"],
    ];
    if (it.stuckMs > 0) rows.splice(2, 0, [t("incidentsMetaStuck"), `${it.stuckMs} ms`]);
    $("incidentModalMeta").innerHTML = rows.map(([k, v]) =>
      `<div class="apps-meta-item"><span>${escapeHtml(k)}</span><strong class="mono">${escapeHtml(String(v))}</strong></div>`,
    ).join("");
    $("incidentModalStack").textContent = it.detail || t("incidentsNoStack");
    modal.classList.remove("hidden");
    modal.setAttribute("aria-hidden", "false");
    document.body.classList.add("apps-modal-open");
    animateModalIn(modal);
    requestAnimationFrame(() => $("incidentModalCloseBtn")?.focus());
  }

  function closeIncidentModal() {
    const modal = $("incidentModal");
    if (!modal || modal.classList.contains("hidden")) return;
    const finish = () => {
      modal.classList.add("hidden");
      modal.setAttribute("aria-hidden", "true");
      document.body.classList.remove("apps-modal-open");
      motionClearInline(modal);
      motionClearInline(modalPanel(modal));
    };
    animateModalOut(modal).then(finish).catch(finish);
  }

  // ── Helpers ───────────────────────────────────────

  const TOAST_HOLD_MS = 1600;
  const TOAST_COALESCE_MS = 1400;
  let toastHideTimer = null;
  let toastClearTimer = null;
  let lastToastKey = "";
  let lastToastAt = 0;

  function ensureFleetToast() {
    let el = $("fleetToast");
    if (el) return el;
    el = document.createElement("div");
    el.id = "fleetToast";
    el.className = "fleet-toast";
    el.hidden = true;
    el.setAttribute("aria-live", "polite");
    el.setAttribute("role", "status");
    document.body.appendChild(el);
    return el;
  }

  function hideFleetToast(el) {
    if (!el) return;
    el.classList.remove("show");
    el.classList.add("hide");
    clearTimeout(toastClearTimer);
    toastClearTimer = setTimeout(() => {
      el.classList.remove("hide", "ok", "err");
      el.hidden = true;
      el.textContent = "";
    }, 120);
  }

  /**
   * Single floating toast (bottom-right). Same key within coalesce window
   * only refreshes the hold timer — no stacked / re-flicker duplicates.
   */
  function showFleetToast(msg, { err = false, key = null, holdMs = TOAST_HOLD_MS } = {}) {
    const text = String(msg || "").trim();
    if (!text) return;
    const el = ensureFleetToast();
    const toastKey = key || text;
    const now = Date.now();
    const visible = !el.hidden && el.classList.contains("show");

    clearTimeout(toastHideTimer);
    clearTimeout(toastClearTimer);

    if (visible && toastKey === lastToastKey && now - lastToastAt < TOAST_COALESCE_MS) {
      lastToastAt = now;
      toastHideTimer = setTimeout(() => hideFleetToast(el), holdMs);
      return;
    }

    lastToastKey = toastKey;
    lastToastAt = now;
    el.textContent = text;
    el.classList.toggle("ok", !err);
    el.classList.toggle("err", !!err);
    el.classList.remove("hide");
    el.hidden = false;
    // Restart enter animation cleanly when message/key changes.
    el.classList.remove("show");
    void el.offsetWidth;
    el.classList.add("show");
    toastHideTimer = setTimeout(() => hideFleetToast(el), holdMs);
  }

  function flash(el, msg, isErr) {
    if (!el) {
      showFleetToast(msg, { err: !!isErr, key: `flash:${msg}` });
      return;
    }
    // Settings / transfer status lines → same floating toast (no duplicate bars).
    if (el.id === "settingsStatus" || el.id === "transferStatus" || el.id === "consolePinStatus") {
      showFleetToast(msg, { err: !!isErr, key: `${el.id}:${msg}` });
      return;
    }
    el.textContent = msg;
    el.className = isErr ? "muted status-err" : "muted status-ok";
    setTimeout(() => { el.textContent = ""; el.className = "muted"; }, 3000);
  }

  const CSELECT_CHEVRON = `<svg class="cselect-chevron" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="M6 9l6 6 6-6"/></svg>`;
  const LOCAL_STAR_SVG = `<svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true" focusable="false"><path fill="currentColor" d="M12 2.8l2.72 5.52 6.09.89-4.4 4.29 1.04 6.06L12 16.7l-5.45 2.86 1.04-6.06-4.4-4.29 6.09-.89L12 2.8z"/></svg>`;

  function localStarHtml() {
    return `<span class="status-star" title="${escapeHtml(t("localBadge"))}" aria-label="${escapeHtml(t("localBadge"))}">${LOCAL_STAR_SVG}</span>`;
  }

  function optionLabelHtml(opt) {
    const text = escapeHtml(opt?.textContent || "—");
    if (opt?.dataset?.local === "1") return `<span>${text}</span>${localStarHtml()}`;
    return `<span>${text}</span>`;
  }

  function closeAllCselects(except) {
    document.querySelectorAll(".cselect.open").forEach((wrap) => {
      if (except && wrap === except) return;
      wrap._cselectClose?.() || wrap.classList.remove("open");
      const btn = wrap.querySelector(".cselect-btn");
      if (btn) btn.setAttribute("aria-expanded", "false");
    });
  }

  function enhanceSelect(select) {
    if (!select || select.tagName !== "SELECT") return;
    if (select.dataset.cselect === "1") {
      select._cselectSync?.();
      return;
    }
    select.dataset.cselect = "1";
    select.classList.add("cselect-native");
    if (!select.classList.contains("input")) select.classList.add("input");

    const wrap = document.createElement("div");
    wrap.className = "cselect";
    select.parentNode.insertBefore(wrap, select);
    wrap.appendChild(select);

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "cselect-btn input";
    btn.setAttribute("aria-haspopup", "listbox");
    btn.setAttribute("aria-expanded", "false");
    btn.innerHTML = `<span class="cselect-value"></span>${CSELECT_CHEVRON}`;
    wrap.appendChild(btn);

    const menu = document.createElement("div");
    menu.className = "cselect-menu";
    menu.setAttribute("role", "listbox");
    wrap.appendChild(menu);

    const valueEl = btn.querySelector(".cselect-value");
    let placeRaf = 0;

    function syncLabel() {
      const opt = select.selectedOptions[0];
      valueEl.innerHTML = optionLabelHtml(opt);
      btn.disabled = !!select.disabled;
    }

    function buildMenu() {
      menu.innerHTML = "";
      [...select.options].forEach((opt) => {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "cselect-option" + (opt.selected ? " on" : "");
        item.setAttribute("role", "option");
        item.setAttribute("aria-selected", opt.selected ? "true" : "false");
        item.innerHTML = optionLabelHtml(opt);
        item.disabled = !!opt.disabled;
        item.addEventListener("click", (ev) => {
          ev.preventDefault();
          ev.stopPropagation();
          if (opt.disabled) return;
          if (select.value !== opt.value) {
            select.value = opt.value;
            select.dispatchEvent(new Event("change", { bubbles: true }));
          }
          syncLabel();
          close();
        });
        menu.appendChild(item);
      });
    }

    function placeMenu() {
      const r = btn.getBoundingClientRect();
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const width = Math.min(Math.max(r.width, 160), vw - 16);
      let left = Math.min(Math.max(8, r.left), vw - width - 8);
      const spaceBelow = vh - r.bottom - 8;
      const spaceAbove = r.top - 8;
      const preferBelow = spaceBelow >= 120 || spaceBelow >= spaceAbove;
      const maxH = Math.min(240, Math.max(preferBelow ? spaceBelow : spaceAbove, 96));
      menu.style.width = `${width}px`;
      menu.style.left = `${left}px`;
      menu.style.maxHeight = `${maxH}px`;
      if (preferBelow) {
        menu.style.top = `${r.bottom + 6}px`;
        menu.style.bottom = "auto";
      } else {
        menu.style.top = "auto";
        menu.style.bottom = `${vh - r.top + 6}px`;
      }
    }

    function onReposition() {
      if (!wrap.classList.contains("open")) return;
      cancelAnimationFrame(placeRaf);
      placeRaf = requestAnimationFrame(placeMenu);
    }

    function open() {
      if (select.disabled) return;
      closeAllCselects(wrap);
      buildMenu();
      document.body.appendChild(menu);
      menu.classList.add("cselect-menu-portal", "is-open");
      placeMenu();
      wrap.classList.add("open");
      btn.setAttribute("aria-expanded", "true");
      window.addEventListener("resize", onReposition);
      window.addEventListener("scroll", onReposition, true);
    }

    function close() {
      wrap.classList.remove("open");
      btn.setAttribute("aria-expanded", "false");
      menu.classList.remove("is-open", "cselect-menu-portal");
      menu.style.top = "";
      menu.style.bottom = "";
      menu.style.left = "";
      menu.style.width = "";
      menu.style.maxHeight = "";
      if (menu.parentNode !== wrap) wrap.appendChild(menu);
      window.removeEventListener("resize", onReposition);
      window.removeEventListener("scroll", onReposition, true);
      cancelAnimationFrame(placeRaf);
    }

    wrap._cselectClose = close;

    btn.addEventListener("click", (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      if (wrap.classList.contains("open")) close();
      else open();
    });

    select.addEventListener("change", syncLabel);
    select._cselectSync = () => {
      syncLabel();
      if (wrap.classList.contains("open")) {
        buildMenu();
        placeMenu();
      }
    };
    syncLabel();
  }

  function enhanceSelects(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll("select.input, .sf-field select, .lang-row select, .logs-bar select").forEach(enhanceSelect);
  }

  if (!window.__avaCselectDocBound) {
    window.__avaCselectDocBound = true;
    document.addEventListener("click", (ev) => {
      if (ev.target.closest?.(".cselect") || ev.target.closest?.(".cselect-menu")) return;
      closeAllCselects();
    });
    document.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape") closeAllCselects();
    });
  }

  // ── Bind ──────────────────────────────────────────

  function bind() {
    document.querySelectorAll(".nav-item[data-view]").forEach((btn) => {
      btn.addEventListener("click", () => {
        if (btn.disabled) return;
        showView(btn.dataset.view);
      });
    });
    $("deviceBackBtn")?.addEventListener("click", () => showView("overview"));
    document.querySelectorAll("[data-device-tab]").forEach((btn) => {
      btn.addEventListener("click", () => {
        applyDeviceTab(btn.dataset.deviceTab);
        if (state.deviceTab !== "screen" && state.streaming) stopScreen();
      });
    });
    $("themeBtn")?.addEventListener("click", () => {
      const cur = resolveTheme((state.prefs || {}).theme || "dark");
      setTheme(cur === "dark" ? "light" : "dark");
    });
    $("deviceFilter").addEventListener("input", () => {
      if (state.status) {
        renderDevices(state.status);
        renderFleetWall(state.status);
      }
    });
    document.querySelectorAll(".view-toggle-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        const mode = btn.dataset.viewMode;
        document.querySelectorAll(".view-toggle-btn").forEach((b) => b.classList.toggle("on", b === btn));
        const grid = $("fleetGridPanel");
        const list = $("devicesTableWrap");
        const isGrid = mode === "grid";
        if (grid) grid.classList.toggle("hidden", !isGrid);
        if (list) list.classList.toggle("hidden", isGrid);
        state.deviceViewMode = mode;
        syncOverviewWallChrome();
        if (isGrid && state.live && state.view === "overview") {
          startWallThumbLoop();
          startWallClock();
        } else {
          stopWallThumbLoop();
          stopWallClock();
        }
        if (!isGrid && state.status) renderDevices(state.status);
      });
    });
    $("consoleLangSelect")?.addEventListener("change", (e) => {
      setLang(e.target.value);
    });
    $("consoleThemeSelect")?.addEventListener("change", (e) => {
      savePrefs({ theme: e.target.value });
    });
    $("consoleDensitySelect")?.addEventListener("change", (e) => {
      savePrefs({ density: e.target.value });
    });
    $("consoleLandingSelect")?.addEventListener("change", (e) => {
      savePrefs({ landing: e.target.value });
    });
    $("consoleAutoLockSelect")?.addEventListener("change", (e) => {
      savePrefs({ autoLockMinutes: Number(e.target.value) || 0 });
    });
    $("consoleFleetTokenClearBtn")?.addEventListener("click", () => {
      const input = $("consoleFleetToken");
      if (input) input.value = DEFAULT_FLEET_PASSWORD;
      savePrefs({ fleetToken: DEFAULT_FLEET_PASSWORD });
      // Stay on login — do not resolveAuthAndStart (that would silent-auto-unlock).
      lockConsole({ resetInput: true, focus: true });
    });
    $("consoleFleetTokenSaveBtn")?.addEventListener("click", () => {
      void saveDeviceAccessPassword();
    });
    $("consoleFleetToken")?.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        void saveDeviceAccessPassword();
      }
    });
    $("authBannerGoBtn")?.addEventListener("click", () => {
      lockConsole({ resetInput: false, focus: true });
    });
    $("consoleLockNowBtn")?.addEventListener("click", () => {
      lockConsole({ resetInput: true, focus: true });
    });
    $("consoleUnlockBtn")?.addEventListener("click", tryUnlock);
    $("consoleLockInput")?.addEventListener("keydown", (e) => {
      if (e.key === "Enter") tryUnlock();
    });
    $("consoleLockInput")?.addEventListener("input", () => {
      clearLockError();
    });
    [
      ["prefLightweight", "lightweight"],
      ["prefLiveUpdates", "liveUpdates"],
      ["prefShowLogsNav", "showLogsNav"],
      ["prefShowTransfer", "showTransfer"],
      ["prefReduceMotion", "reduceMotion"],
    ].forEach(([id, key]) => {
      $(id)?.addEventListener("change", (e) => {
        savePrefs({ [key]: !!e.target.checked });
      });
    });
    document.addEventListener("pointerdown", touchActivity, { passive: true });
    document.addEventListener("keydown", touchActivity);
    window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => {
      if ((state.prefs || {}).theme === "system") applyPrefs();
    });
    window.addEventListener("ava-i18n", applyI18nWithTransition);
    $("screenStartBtn").addEventListener("click", startScreen);
    $("screenStopBtn").addEventListener("click", stopScreen);
    $("screenEnableA11yBtn")?.addEventListener("click", openAccessibilitySettings);
    // Stage context menu → Android Back while streaming (trackpad secondary).
    const screenStage = $("screenStage");
    if (screenStage) {
      screenStage.addEventListener("contextmenu", onScreenContextMenu);
    }
    document.addEventListener("keydown", (e) => {
      if (e.key !== "Escape") return;
      const appsModal = $("appsDetailPanel");
      if (appsModal && !appsModal.classList.contains("hidden")) {
        e.preventDefault();
        closeAppsDetailModal();
        renderAppsList();
        return;
      }
      const incidentModal = $("incidentModal");
      if (incidentModal && !incidentModal.classList.contains("hidden")) {
        e.preventDefault();
        closeIncidentModal();
        return;
      }
      if (state.deviceTab !== "screen" || !state.streaming) return;
      // Don't steal Escape from inputs / dialogs.
      const tag = (e.target && e.target.tagName) || "";
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || e.target?.isContentEditable) return;
      e.preventDefault();
      requestScreenBack();
    });
    // The live <img> is created lazily by ensureLiveImg(); pointer handlers are
    // attached there rather than on the initial DOM.
    $("shellClearBtn")?.addEventListener("click", clearShellTerminal);
    $("shellFocusBtn")?.addEventListener("click", () => {
      ensureShellTerminal();
      try { state.term?.focus(); } catch (_) {}
    });
    $("telemetryDeepBtn")?.addEventListener("click", refreshDeepTelemetry);
    window.addEventListener("resize", () => {
      if (state.status?.telemetry) renderCharts(state.status.telemetry);
      // Rebuild wall when crossing mobile/desktop column rules.
      if (state.view === "overview" && state.deviceViewMode !== "list" && state.status) {
        const next = wallStructureSig(fleetDevices(state.status));
        if (next !== state.wallSig) renderFleetWall(state.status);
      }
    });

    $("settingsExportBtn")?.addEventListener("click", exportSettings);
    $("settingsImportBtn")?.addEventListener("click", openImportModal);
    $("settingsTransferBtn")?.addEventListener("click", toggleTransferPanel);
    // Toggle expand/collapse — one button cycles both states
    $("settingsToggleBtn")?.addEventListener("click", () => {
      const catalog = $("settingsCatalog");
      if (!catalog) return;
      const groups = [...catalog.querySelectorAll("details.sf-group")];
      const allOpen = groups.every((g) => g.open);
      setCatalogExpanded(!allOpen);
      const btn = $("settingsToggleBtn");
      if (btn) btn.textContent = allOpen ? t("settingsExpandAll") : t("settingsCollapseAll");
    });
    $("settingsMiniNav")?.addEventListener("click", (e) => {
      const btn = e.target.closest(".settings-mini-item");
      if (!btn?.dataset.group) return;
      e.preventDefault();
      focusSettingsGroup(btn.dataset.group, { scroll: true });
    });
    $("jsonFormatBtn")?.addEventListener("click", formatJsonEditor);
    $("jsonReloadBtn")?.addEventListener("click", reloadJsonFromForm);
    $("jsonEditor")?.addEventListener("input", onJsonEditorInput);
    $("jsonEditor")?.addEventListener("blur", onJsonEditorBlur);

    $("importCloseBtn")?.addEventListener("click", closeImportModal);
    $("transferCloseBtn")?.addEventListener("click", closeTransferModal);
    $("transferModal")?.addEventListener("click", (e) => {
      if (e.target === $("transferModal")) closeTransferModal();
    });

    $("adbAddCloseBtn")?.addEventListener("click", closeAdbAddModal);
    $("adbOpenPairBtn")?.addEventListener("click", () => {
      setAdbAddStatus("");
      showAdbAddPanel("pair");
    });
    $("adbPairBackBtn")?.addEventListener("click", () => {
      setAdbAddStatus("");
      showAdbAddPanel("main");
    });
    $("adbPairBtn")?.addEventListener("click", () => { void adbPair(); });
    $("adbConnectBtn")?.addEventListener("click", () => { void adbConnect(); });
    $("adbRefreshBtn")?.addEventListener("click", () => { void adbRefreshDevices(); });
    $("adbAddModal")?.addEventListener("click", (e) => {
      if (e.target === $("adbAddModal")) closeAdbAddModal();
    });
    // Shared entry: wall tile + devices list button (+ empty-state CTA).
    document.addEventListener("click", (e) => {
      const btn = e.target?.closest?.("[data-adb-add], #fleetWallAddBtn, #devicesAddBtn, .dev-tile-add");
      if (!btn) return;
      e.preventDefault();
      openAdbAddModal();
    });

    $("appsRefreshBtn")?.addEventListener("click", () => { void loadAppsView(true); });
    $("appsDeviceSelect")?.addEventListener("change", () => {
      state.appsSerial = appsSerialValue();
      void loadAppsView(true);
    });
    document.querySelectorAll(".files-th-sort").forEach((th) => {
      th.addEventListener("click", () => {
        const key = th.dataset.sort;
        if (state.filesSort === key) {
          state.filesSortAsc = !state.filesSortAsc;
        } else {
          state.filesSort = key;
          state.filesSortAsc = key === "name";
        }
        filesRenderList();
      });
    });
    $("filesRefreshBtn")?.addEventListener("click", () => { void loadFilesView(true); });
    $("filesDeviceSelect")?.addEventListener("change", () => {
      filesCacheInvalidate(state.filesSerial);
      state.filesSerial = filesSerialValue();
      state.filesPath = "/sdcard";
      void loadFilesView(true);
    });
    $("filesHomeBtn")?.addEventListener("click", () => { filesGoHome(); });
    $("filesMkdirBtn")?.addEventListener("click", () => { void filesCreateFolder(); });
    $("filesUploadInput")?.addEventListener("change", (e) => {
      const input = e.target;
      void filesUploadPicked(input?.files);
      if (input) input.value = "";
    });
    $("appsFilter")?.addEventListener("input", () => {
      state.appsQuery = $("appsFilter").value || "";
      state.appsPage = 1;
      renderAppsList();
    });
    document.querySelectorAll("[data-apps-filter]").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.appsFilter = btn.getAttribute("data-apps-filter") || "user";
        state.appsPage = 1;
        document.querySelectorAll("[data-apps-filter]").forEach((b) => {
          b.classList.toggle("on", b === btn);
        });
        void loadAppsView(true);
      });
    });
    $("appsPagePrev")?.addEventListener("click", () => {
      if (state.appsPage <= 1) return;
      state.appsPage -= 1;
      renderAppsList();
    });
    $("appsPageNext")?.addEventListener("click", () => {
      const total = Array.isArray(state.appsList) ? state.appsList.length : 0;
      const pageCount = Math.max(1, Math.ceil(total / state.appsPageSize));
      if (state.appsPage >= pageCount) return;
      state.appsPage += 1;
      renderAppsList();
    });
    $("appsDetailCloseBtn")?.addEventListener("click", () => {
      closeAppsDetailModal();
      renderAppsList();
    });
    $("appsDetailPanel")?.addEventListener("click", (e) => {
      if (e.target.closest?.("[data-apps-detail-dismiss]")) {
        closeAppsDetailModal();
        renderAppsList();
      }
    });
    $("appsDetailActions")?.querySelectorAll("[data-apps-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        void appsRunAction(btn.getAttribute("data-apps-action"));
      });
    });
    $("appsPullApkBtn")?.addEventListener("click", () => { void appsPullApk(); });
    $("appsInstallInput")?.addEventListener("change", () => {
      const f = $("appsInstallInput").files?.[0];
      if (f) void appsInstallFile(f);
      $("appsInstallInput").value = "";
    });

    $("importApplyBtn")?.addEventListener("click", applyImport);
    $("importFileInput")?.addEventListener("change", () => {
      const f = $("importFileInput").files?.[0];
      if (f) $("importFileName").textContent = f.name;
    });

    $("transferProbeBtn")?.addEventListener("click", probePeer);
    $("transferPullBtn")?.addEventListener("click", pullSettings);
    $("transferPushBtn")?.addEventListener("click", pushSettings);
    $("transferApplyBtn")?.addEventListener("click", applyPulledSettings);
    $("transferCancelBtn")?.addEventListener("click", cancelPulledReview);

    $("logsDeviceSelect")?.addEventListener("change", () => {
      state.logsSerial = logsSerialValue();
      clearLogs();
      state.logsLastFetchAt = 0;
      void refreshLogs();
    });
    $("logsLiveChk") && ($("logsLiveChk").checked = !!state.logsLive);
    $("logsLiveChk")?.addEventListener("change", (e) => {
      state.logsLive = !!e.target.checked;
      if (state.logsLive && state.view === "activity") startLogsPolling();
      else stopLogsPolling();
    });
    $("logsRefreshBtn")?.addEventListener("click", () => {
      state.logsLastFetchAt = 0;
      void refreshLogs();
    });
    $("logsClearBtn")?.addEventListener("click", clearLogs);

    $("incidentsRefreshBtn")?.addEventListener("click", () => {
      void refreshIncidents({ force: true });
    });
    $("incidentsList")?.addEventListener("click", (e) => {
      const row = e.target.closest?.("[data-incident]");
      if (row) openIncidentModal(Number(row.dataset.incident));
    });
    $("incidentModalCloseBtn")?.addEventListener("click", closeIncidentModal);
    $("incidentModal")?.addEventListener("click", (e) => {
      if (e.target.closest?.("[data-incident-dismiss]")) closeIncidentModal();
    });

    $("importModal")?.addEventListener("click", (e) => {
      if (e.target === $("importModal")) closeImportModal();
    });

    document.addEventListener("visibilitychange", () => {
      if (document.hidden) {
        stopWallThumbLoop();
      } else if (state.live && state.view === "overview") {
        startWallThumbLoop(true);
      }
    });
  }

  initTheme();
  bootstrapFleetTokenFromUrl();
  bind();
  applyStaticI18n();
  enhanceSelects();
  state.booted = true;
  applyPrefs();
  if (!motionLib()) {
    console.warn("[fleet] Motion CDN not loaded — UI animations disabled");
  }
  setLiveState("connecting");
  // Auth gate first. Live SSE / refresh / dashboard only start after unlock.
  void resolveAuthAndStart();
})();
