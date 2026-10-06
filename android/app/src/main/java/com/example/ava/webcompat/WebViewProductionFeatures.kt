package com.example.ava.webcompat

import android.util.Log
import java.io.File

/**
 * Silent best-effort merge of Chromium production features into
 * `/data/local/tmp/webview-command-line` when the device allows it.
 *
 * Overlay-facing behavior (cache purge, pre-raster) lives in [WebViewService];
 * this helper only nudges the system WebView engine when writable.
 */
object WebViewProductionFeatures {
    private const val TAG = "WebViewProdFeatures"
    private const val COMMAND_LINE_PATH = "/data/local/tmp/webview-command-line"

    /** Group A — memory. */
    const val V8_MEMORY_REDUCER = "V8MemoryReducer"
    const val WEBVIEW_PURGE_MEMORY_IN_BACKGROUND = "WebViewPurgeMemoryInBackground"

    /** Group B — frame / timer / video throttle. */
    const val THROTTLE_UNIMPORTANT_FRAME_TIMERS = "ThrottleUnimportantFrameTimers"
    const val THROTTLE_MAIN_FRAME_TO60_HZ_WEBVIEW = "ThrottleMainFrameTo60HzWebView"
    const val ON_BEGIN_FRAME_THROTTLE_VIDEO = "OnBeginFrameThrottleVideo"

    val MEMORY_FEATURES: List<String> = listOf(
        V8_MEMORY_REDUCER,
        WEBVIEW_PURGE_MEMORY_IN_BACKGROUND,
    )

    val FRAME_THROTTLE_FEATURES: List<String> = listOf(
        THROTTLE_UNIMPORTANT_FRAME_TIMERS,
        THROTTLE_MAIN_FRAME_TO60_HZ_WEBVIEW,
        ON_BEGIN_FRAME_THROTTLE_VIDEO,
    )

    private val ALL_MANAGED: Set<String> =
        (MEMORY_FEATURES + FRAME_THROTTLE_FEATURES).toSet()

    /**
     * Oldest Chromium where the full managed flag set exists (best-effort/conservative).
     * Engines silently ignore unknown base::Feature names, so *writing* the flags is
     * always safe on any version — but reporting them ACTIVE on an engine that predates
     * them is a lie. Used only for honest reporting in settings.
     */
    const val FLAGS_MIN_CHROMIUM = 126

    enum class ApplyStatus {
        /** Command-line file updated (or cleared) successfully. */
        COMMAND_LINE_APPLIED,
        /** Nothing to apply (both groups off and no managed features present). */
        IDLE,
        /** File not writable on this device; overlay policies still apply. */
        NEEDS_DEVTOOLS,
        /** Unexpected failure while reading/writing. */
        FAILED,
    }

    data class ApplyResult(
        val status: ApplyStatus,
        val features: List<String>,
        val detail: String = "",
    )

    /** How far the two switches actually reached, for the settings screen to state plainly. */
    enum class EngineFlagState {
        /** No apply attempt in this process yet. */
        UNKNOWN,
        /** Chromium booted with exactly the requested flags. */
        ACTIVE,
        /** Written, but the running engine still holds the old set. */
        RESTART_REQUIRED,
        /** Command line not writable here; only the overlay-side policies apply. */
        UNAVAILABLE,
        /** Written fine, but the engine predates some/all managed flags and ignores them. */
        ENGINE_TOO_OLD,
    }

    /** Flag set the engine booted with, i.e. the first apply of this process. */
    @Volatile private var bootFeatures: List<String>? = null
    @Volatile private var lastResult: ApplyResult? = null

    /**
     * Chromium only reads the command line when the WebView provider starts, so a toggle flipped
     * later cannot be in effect no matter how well the write went. Report that instead of letting
     * the switch imply otherwise.
     *
     * @param chromeMajor installed Chromium major version (see [WebViewRuntime]); 0 = unknown,
     *   which stays permissive. Engines older than [FLAGS_MIN_CHROMIUM] ignore some or all of
     *   the managed flags, so a clean write must not be reported as ACTIVE there.
     */
    fun engineFlagState(chromeMajor: Int = 0): EngineFlagState {
        val result = lastResult ?: return EngineFlagState.UNKNOWN
        if (result.status == ApplyStatus.NEEDS_DEVTOOLS || result.status == ApplyStatus.FAILED) {
            return EngineFlagState.UNAVAILABLE
        }
        if (result.features.isNotEmpty() && chromeMajor in 1 until FLAGS_MIN_CHROMIUM) {
            return EngineFlagState.ENGINE_TOO_OLD
        }
        val boot = bootFeatures ?: return EngineFlagState.UNKNOWN
        return if (result.features.toSet() == boot.toSet()) {
            EngineFlagState.ACTIVE
        } else {
            EngineFlagState.RESTART_REQUIRED
        }
    }

    fun featuresFor(memoryEnabled: Boolean, frameThrottleEnabled: Boolean): List<String> {
        val out = ArrayList<String>(5)
        if (memoryEnabled) out.addAll(MEMORY_FEATURES)
        if (frameThrottleEnabled) out.addAll(FRAME_THROTTLE_FEATURES)
        return out
    }

    fun applyDesired(memoryEnabled: Boolean, frameThrottleEnabled: Boolean): ApplyResult {
        val desired = featuresFor(memoryEnabled, frameThrottleEnabled)
        val result = try {
            mergeCommandLine(desired)
        } catch (e: SecurityException) {
            Log.i(TAG, "command-line not writable: ${e.message}")
            ApplyResult(ApplyStatus.NEEDS_DEVTOOLS, desired, e.message.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "applyDesired failed", e)
            ApplyResult(ApplyStatus.FAILED, desired, e.message.orEmpty())
        }
        lastResult = result
        if (bootFeatures == null) bootFeatures = result.features
        return result
    }

    fun isCommandLineWritable(): Boolean {
        val file = File(COMMAND_LINE_PATH)
        return try {
            when {
                file.exists() -> file.canWrite()
                else -> {
                    val parent = file.parentFile ?: return false
                    parent.exists() && parent.canWrite()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun mergeCommandLine(desiredFeatures: List<String>): ApplyResult {
        val file = File(COMMAND_LINE_PATH)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            // Standard path on Android; if missing we cannot create /data/local/tmp.
            return ApplyResult(ApplyStatus.NEEDS_DEVTOOLS, desiredFeatures, "missing tmp")
        }
        if (!isCommandLineWritable() && !file.exists()) {
            // Probe create — may throw on production user builds.
            try {
                file.writeText("_\n")
                file.delete()
            } catch (_: Exception) {
                return ApplyResult(ApplyStatus.NEEDS_DEVTOOLS, desiredFeatures, "not writable")
            }
        }

        val raw = if (file.exists()) {
            try {
                file.readText()
            } catch (e: Exception) {
                return ApplyResult(ApplyStatus.NEEDS_DEVTOOLS, desiredFeatures, e.message.orEmpty())
            }
        } else {
            ""
        }

        val tokens = tokenizeCommandLine(raw).toMutableList()
        if (tokens.isEmpty()) {
            tokens.add("_")
        }

        var enableIdx = -1
        var enableValue = ""
        for (i in tokens.indices) {
            val t = tokens[i]
            when {
                t.startsWith("--enable-features=") -> {
                    enableIdx = i
                    enableValue = t.removePrefix("--enable-features=")
                }
                t == "--enable-features" && i + 1 < tokens.size -> {
                    enableIdx = i
                    enableValue = tokens[i + 1]
                    tokens.removeAt(i + 1)
                }
            }
        }

        val existing = enableValue
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        existing.removeAll { it.substringBefore('<').substringBefore(':') in ALL_MANAGED }

        val desiredSet = desiredFeatures.toSet()
        for (f in desiredFeatures) {
            if (existing.none { it.substringBefore('<').substringBefore(':') == f }) {
                existing.add(f)
            }
        }
        // Keep stable order: non-managed first (already filtered), then desired.
        val merged = existing.distinct()

        if (enableIdx >= 0) {
            if (merged.isEmpty()) {
                tokens.removeAt(enableIdx)
            } else {
                tokens[enableIdx] = "--enable-features=${merged.joinToString(",")}"
            }
        } else if (merged.isNotEmpty()) {
            tokens.add("--enable-features=${merged.joinToString(",")}")
        }

        // Drop our managed features from --disable-features if present (avoid conflict).
        stripManagedFromDisable(tokens)

        val onlyPlaceholder = tokens.size == 1 && (tokens[0] == "_" || tokens[0].isBlank())
        if (onlyPlaceholder || tokens.isEmpty()) {
            if (file.exists()) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "delete empty command-line failed", e)
                }
            }
            return if (desiredSet.isEmpty()) {
                ApplyResult(ApplyStatus.IDLE, emptyList())
            } else {
                // Desired non-empty but we cleared? Should not happen.
                ApplyResult(ApplyStatus.FAILED, desiredFeatures, "empty after merge")
            }
        }

        val line = tokens.joinToString(" ")
        return try {
            file.writeText(line + "\n")
            Log.i(TAG, "Wrote $COMMAND_LINE_PATH → $line")
            if (desiredSet.isEmpty()) {
                ApplyResult(ApplyStatus.COMMAND_LINE_APPLIED, emptyList(), "cleared managed")
            } else {
                ApplyResult(ApplyStatus.COMMAND_LINE_APPLIED, desiredFeatures)
            }
        } catch (e: Exception) {
            Log.i(TAG, "write failed: ${e.message}")
            ApplyResult(ApplyStatus.NEEDS_DEVTOOLS, desiredFeatures, e.message.orEmpty())
        }
    }

    private fun stripManagedFromDisable(tokens: MutableList<String>) {
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.startsWith("--disable-features=")) {
                val kept = t.removePrefix("--disable-features=")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filterNot { it.substringBefore('<').substringBefore(':') in ALL_MANAGED }
                if (kept.isEmpty()) {
                    tokens.removeAt(i)
                    continue
                }
                tokens[i] = "--disable-features=${kept.joinToString(",")}"
            }
            i++
        }
    }

    /** Chromium ignores argv[0]; remaining tokens are switches. */
    private fun tokenizeCommandLine(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // Single-line file; split on whitespace. Feature lists contain no spaces.
        return raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}
