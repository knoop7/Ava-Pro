(function () {
  "use strict";

  const NATIVE_APP = "avaWebConsole";
  const MAX_MESSAGE_LENGTH = 8192;
  const port = browser.runtime.connectNative(NATIVE_APP);
  const page = window.wrappedJSObject;
  let captureEnabled = false;

  function formatValue(value) {
    if (typeof value === "string") return value;
    try {
      if (value && typeof value.stack === "string") return value.stack;
    } catch (_) {}
    try {
      const json = page.JSON.stringify(value);
      if (json !== undefined) return String(json);
    } catch (_) {}
    try {
      return String(value);
    } catch (_) {
      return "[unprintable]";
    }
  }

  function send(message) {
    try {
      port.postMessage(message);
    } catch (_) {}
  }

  /**
   * Run code in the page world. Prefer wrappedJSObject.eval (returns a value).
   * If page CSP blocks eval, fall back to a synchronous <script> tag so toggles
   * still install. WebView addDocumentStartJavaScript is not CSP-bound; this is
   * the closest Gecko content-script equivalent.
   */
  function runInPage(code) {
    const src = String(code);
    try {
      return { ok: true, value: page.eval(src) };
    } catch (evalError) {
      try {
        const key = "__ava_eval_" + Math.random().toString(36).slice(2);
        const wrapped =
          "try{window[" + JSON.stringify(key) + "]=(function(){return(" + src + ");})();}" +
          "catch(e){window[" + JSON.stringify(key) + "]={__avaErr:String(e&&e.message||e)};}";
        const script = document.createElement("script");
        script.setAttribute("type", "text/javascript");
        script.textContent = wrapped;
        const root = document.documentElement || document.head || document.body;
        if (!root) throw evalError;
        root.appendChild(script);
        script.remove();
        const value = page[key];
        try { delete page[key]; } catch (_) {}
        if (value && value.__avaErr) return { ok: false, error: value.__avaErr };
        return { ok: true, value: value };
      } catch (_) {
        return { ok: false, error: evalError };
      }
    }
  }

  function installConsoleForwarding() {
    if (!page || !page.console || page.__avaWebConsoleBridgeInstalled) return;
    page.__avaWebConsoleBridgeInstalled = true;

    ["log", "info", "warn", "error", "debug"].forEach(function (level) {
      const original = page.console[level];
      if (typeof original !== "function") return;
      const forwardingConsoleMethod = function () {
        const args = Array.prototype.slice.call(arguments);
        if (captureEnabled) {
          const text = args.map(formatValue).join(" ").slice(0, MAX_MESSAGE_LENGTH);
          if (text) send({ type: "console", level: level, message: text });
        }
        return original.apply(page.console, args);
      };
      page.console[level] = exportFunction(forwardingConsoleMethod, page.console);
    });
  }

  function sendEvalResult(id, value) {
    send({ type: "eval-result", id: id, value: formatValue(value) });
  }

  function sendEvalError(id, error) {
    send({ type: "eval-error", id: id, message: formatValue(error) });
  }

  function applyScripts(scripts) {
    if (!scripts || !scripts.length) return;
    for (let i = 0; i < scripts.length; i++) {
      const result = runInPage(scripts[i]);
      if (!result.ok) {
        send({
          type: "console",
          level: "error",
          message: "[Ava] init script failed: " + formatValue(result.error),
        });
      }
    }
  }

  port.onMessage.addListener(function (message) {
    if (message && message.type === "set-enabled") {
      captureEnabled = message.enabled === true;
      return;
    }
    // Kotlin pushes these on every content-script connect (document_start of the
    // new document). Delivery is still one IPC hop later than WebView's
    // addDocumentStartJavaScript; scripts are idempotent and Kotlin also
    // re-installs after onPageStop.
    if (message && message.type === "init-scripts") {
      applyScripts(message.scripts);
      return;
    }
    if (!message || message.type !== "eval" || typeof message.code !== "string") return;
    const result = runInPage(message.code);
    if (result.ok) {
      sendEvalResult(message.id, result.value);
    } else {
      sendEvalError(message.id, result.error);
    }
  });

  installConsoleForwarding();
})();
