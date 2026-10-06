package com.example.ava.openwakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWakeWordCutoffTest {
    @Test
    fun sanitizeClampsToOpenWakeWordRange() {
        assertEquals(0.15f, OpenWakeWordCutoffPolicy.sanitize(0f), 0.0001f)
        assertEquals(0.99f, OpenWakeWordCutoffPolicy.sanitize(1f), 0.0001f)
        assertEquals(0.5f, OpenWakeWordCutoffPolicy.sanitize(0.5f), 0.0001f)
    }

    @Test
    fun sliderStaysInsideTheModelsWorkingRange() {
        val range = OpenWakeWordCutoffPolicy.sliderRange(0.5f)
        assertEquals(0.15f, range.start, 0.0001f)
        assertEquals(0.80f, range.endInclusive, 0.0001f)
        // A 0.5-calibrated model must not be offered 0.87–0.99, where measured
        // recall collapsed (5/8 at 0.87, 0/8 at 0.92).
        assertTrue(0.87f > range.endInclusive)
        assertTrue(0.97f > range.endInclusive)
    }

    @Test
    fun leftoverMicroCutoffClampsDownInsteadOfLoosening() {
        // Shared sensitivity fields still hold 0.97 from bundled hey_jarvis / hey_luna.
        // Resolving those to the 0.5 manifest turned "strictest available" into the
        // loosest value the model has — 14 false wakes per 23 min of speech instead of 0.
        assertEquals(
            0.80f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.97f),
            0.0001f,
        )
        assertEquals(
            0.80f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.87f),
            0.0001f,
        )
        // Unset / missing override keeps the manifest.
        assertEquals(
            0.5f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, -1f),
            0.0001f,
        )
        // An in-range open setting is kept.
        assertEquals(
            0.65f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.65f),
            0.0001f,
        )
        assertEquals(
            0.80f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.80f),
            0.0001f,
        )
    }

    @Test
    fun resolutionNeverReturnsLooserThanRequested() {
        // The property the old fallback broke: asking for stricter may be capped, but it
        // may never come back looser than the manifest the user was already running.
        val baseline = 0.5f
        var requested = baseline
        while (requested <= 1.0f) {
            val resolved = OpenWakeWordCutoffPolicy.resolveRequestedCutoff(baseline, requested)
            assertTrue(
                "requested=$requested resolved=$resolved must not be looser than $baseline",
                resolved >= baseline,
            )
            assertTrue(resolved <= requested)
            requested += 0.01f
        }
    }

    @Test
    fun subFloorRequestIsTreatedAsUnset() {
        // 0.15 already sits in the noise; anything under it carries no legible intent,
        // so it stays "unset" rather than clamping the detector down into the noise.
        assertEquals(
            0.5f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.05f),
            0.0001f,
        )
    }

    @Test
    fun manifestAboveCeilingStaysReachable() {
        // The 0.80 ceiling blocks micro-style leftovers, not the model author's own
        // calibration. A manifest shipping 0.85 keeps 0.85 as its reachable maximum —
        // otherwise an untouched slider would silently save a looser 0.80.
        val range = OpenWakeWordCutoffPolicy.sliderRange(0.85f)
        assertEquals(0.85f, range.endInclusive, 0.0001f)
        assertEquals(
            0.85f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.85f, 0.85f),
            0.0001f,
        )
        // Values above the manifest are still rejected back to it.
        assertEquals(
            0.85f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.85f, 0.97f),
            0.0001f,
        )
    }

    @Test
    fun jarvisLegacyBumpDoesNotApplyToOpenCutoffs() {
        // Micro's 0.85→0.97 rewrite is id-based, so open must not inherit that value —
        // but it still resolves to the ceiling, not back down to the 0.5 manifest.
        assertEquals(
            0.80f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.85f),
            0.0001f,
        )
    }

    /**
     * Installed manifests carry a literal `window=1, hits=1` that our own writer put
     * there, not a model author. Detection resolves that never-chosen combination to
     * the measured consecutive-hits gate, so those models stop firing on single-frame
     * spikes without being reinstalled.
     */
    @Test
    fun installedRawScoreManifestsGetTheConsecutiveHitsGate() {
        val json = """
            {
              "type": "openwakeword",
              "format": "openwakeword-v1",
              "id": "ok_nabu",
              "wake_word": "Ok Nabu",
              "model": "ok_nabu.onnx",
              "openwakeword": { "threshold": 0.5, "required_hits": 1, "cooldown_ms": 2000 }
            }
        """.trimIndent()
        val manifest = OpenWakeWordManifest.fromJson(json)!!
        // The parsed manifest still mirrors the file.
        assertEquals(1, manifest.openwakeword?.slidingWindowSize)
        assertEquals(1, manifest.openwakeword?.requiredHits)
        val model = OpenWakeWordModel("ok_nabu", manifest) { ByteArray(0) }
        assertEquals(OpenWakeWordCutoffPolicy.DEFAULT_SLIDING_WINDOW, model.slidingWindowSize)
        assertEquals(OpenWakeWordCutoffPolicy.DEFAULT_REQUIRED_HITS, model.requiredHits)
    }

    /** A deliberately tuned gate — any window or hits beyond 1 — runs exactly as written. */
    @Test
    fun authorTunedGatesAreRespectedAsWritten() {
        fun modelFor(runtime: String): OpenWakeWordModel {
            val json = """
                {
                  "type": "openwakeword",
                  "format": "openwakeword-v1",
                  "id": "ok_nabu",
                  "model": "ok_nabu.onnx",
                  "openwakeword": $runtime
                }
            """.trimIndent()
            return OpenWakeWordModel("ok_nabu", OpenWakeWordManifest.fromJson(json)!!) { ByteArray(0) }
        }

        // An author-chosen averaging window keeps hits=1 rather than stacking both gates.
        val windowed = modelFor("""{ "threshold": 0.4, "sliding_window_size": 3 }""")
        assertEquals(0.4f, windowed.threshold, 0.0001f)
        assertEquals(3, windowed.slidingWindowSize)
        assertEquals(1, windowed.requiredHits)
        assertEquals(9, modelFor("""{ "sliding_window_size": 9 }""").slidingWindowSize)

        // An author-chosen hit count keeps its raw window.
        val hitsTuned = modelFor("""{ "required_hits": 3 }""")
        assertEquals(1, hitsTuned.slidingWindowSize)
        assertEquals(3, hitsTuned.requiredHits)
    }

    /** A freshly downloaded model must not be written back out as a raw-score manifest. */
    @Test
    fun newlyWrappedManifestDeclaresTheDefaultGate() {
        val manifest = OpenWakeWordManifest.wrapOnnx(id = "hey_luna", wakeWord = "Hey Luna")
        assertEquals(
            OpenWakeWordCutoffPolicy.DEFAULT_SLIDING_WINDOW,
            manifest.openwakeword?.slidingWindowSize,
        )
        assertEquals(
            OpenWakeWordCutoffPolicy.DEFAULT_REQUIRED_HITS,
            manifest.openwakeword?.requiredHits,
        )
    }

    /** The bundled manifest must declare the measured gate rather than rely on resolution. */
    @Test
    fun bundledOkNabuDeclaresTheConsecutiveHitsGate() {
        val json = java.io.File("src/main/assets/openwakeword/ok_nabu.json").readText()
        val manifest = OpenWakeWordManifest.fromJson(json)!!
        assertEquals(1, manifest.openwakeword?.slidingWindowSize)
        assertEquals(2, manifest.openwakeword?.requiredHits)
    }

    /**
     * Verifier models (official hey_jarvis: an ONNX `If` gates a second network) emit
     * one narrow spike per utterance by design. The hits=2 upgrade measurably costs
     * recall there (5/8 vs 7/8) and buys nothing — genuine negatives never exceed 0.33 —
     * so their manifest gate runs as written.
     */
    @Test
    fun builtInVerifierKeepsTheManifestGate() {
        val gate = OpenWakeWordCutoffPolicy.effectiveGate(1, 1, hasBuiltInVerifier = true)
        assertEquals(1, gate.slidingWindow)
        assertEquals(1, gate.requiredHits)
        // Without the verifier the same manifest still gets the measured upgrade.
        val upgraded = OpenWakeWordCutoffPolicy.effectiveGate(1, 1, hasBuiltInVerifier = false)
        assertEquals(OpenWakeWordCutoffPolicy.DEFAULT_REQUIRED_HITS, upgraded.requiredHits)
        // An author-tuned gate on a verifier model is still respected as written.
        val tuned = OpenWakeWordCutoffPolicy.effectiveGate(3, 1, hasBuiltInVerifier = true)
        assertEquals(3, tuned.slidingWindow)
        assertEquals(1, tuned.requiredHits)
    }

    /**
     * hey_jarvis's spike height varies with the speaker (0.33–0.98 across voices), so a
     * micro-side leftover 0.97 clamped to the 0.80 ceiling is not "strict" — it is deaf:
     * measured 0/8 positives at 0.80 vs 5/8+ at the 0.5 manifest. For verifier models an
     * out-of-range leftover is treated as unset instead.
     */
    @Test
    fun verifierModelsDropOutOfRangeLeftoversToTheManifest() {
        assertEquals(
            0.5f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.97f, hasBuiltInVerifier = true),
            0.0001f,
        )
        // A moderate value the verifier-specific slider can reach is kept.
        assertEquals(
            0.65f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.65f, hasBuiltInVerifier = true),
            0.0001f,
        )
        // 0.80 was offered by the old generic slider but measured 0/8 on Jarvis;
        // treat it as stale and restore the model recommendation.
        assertEquals(
            0.5f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.80f, hasBuiltInVerifier = true),
            0.0001f,
        )
        assertEquals(
            0.15f..0.65f,
            OpenWakeWordCutoffPolicy.sliderRange(0.5f, hasBuiltInVerifier = true),
        )
        // Non-verifier models keep the measured ceiling clamp (ok_nabu: same recall at
        // 0.80, and honoring "as strict as reachable" costs nothing).
        assertEquals(
            0.80f,
            OpenWakeWordCutoffPolicy.resolveRequestedCutoff(0.5f, 0.97f, hasBuiltInVerifier = false),
            0.0001f,
        )
    }

    /** The `If` scan matches the exact NodeProto marker, not other op names. */
    @Test
    fun verifierDetectionMatchesTheIfOpMarker() {
        fun model(bytes: ByteArray) =
            OpenWakeWordModel("x", OpenWakeWordManifest.wrapOnnx("x", "X")) { bytes }

        val ifMarker = byteArrayOf(0x22, 0x02, 'I'.code.toByte(), 'f'.code.toByte())
        assertTrue(model(byteArrayOf(0x0A, 0x03) + ifMarker + byteArrayOf(0x1A)).hasBuiltInVerifier)
        // "Identity" (length 8) must not match, nor a bare "If" without the tag.
        val identity = byteArrayOf(0x22, 0x08) + "Identity".toByteArray(Charsets.US_ASCII)
        assertTrue(!model(identity).hasBuiltInVerifier)
        assertTrue(!model("If".toByteArray(Charsets.US_ASCII)).hasBuiltInVerifier)
        assertTrue(!model(ByteArray(0)).hasBuiltInVerifier)

        val staleFalse = OpenWakeWordManifest.wrapOnnx(
            id = "stale",
            wakeWord = "Stale",
            builtInVerifier = false,
        )
        assertTrue(
            OpenWakeWordModel("stale", staleFalse) { ifMarker }.hasBuiltInVerifier,
        )
    }

    @Test
    fun verifierHintSurvivesManifestRoundTripWithoutLoadingWeights() {
        val manifest = OpenWakeWordManifest.wrapOnnx(
            id = "custom_verifier",
            wakeWord = "Custom Verifier",
            builtInVerifier = true,
        )
        val restored = OpenWakeWordManifest.fromJson(
            OpenWakeWordManifest.toJsonObject(manifest).toString(),
        )!!
        assertTrue(restored.builtInVerifier == true)
        val model = OpenWakeWordModel("custom_verifier", restored) {
            error("manifest hint must avoid loading weights")
        }
        assertTrue(model.verifierHint)
        assertTrue(model.hasBuiltInVerifier)
    }

    @Test
    fun offlineVerifyCutoffRaisesOnlyInTheExtraZone() {
        assertEquals(
            0.50f,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.50f, 0),
            0.0001f,
        )
        assertEquals(
            0.54f,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.50f, 1),
            0.0001f,
        )
        assertEquals(
            0.58f,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.50f, 2),
            0.0001f,
        )
        // Native ceiling + extra zone stays inside measured peak headroom (0.84–0.91).
        // L1 and L2 share the cap; L2's extra rejection is hits/VAD, not 0.88.
        assertEquals(
            OpenWakeWordCutoffPolicy.EXTRA_VERIFY_CAP,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.80f, 1),
            0.0001f,
        )
        assertEquals(
            OpenWakeWordCutoffPolicy.EXTRA_VERIFY_CAP,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.80f, 2),
            0.0001f,
        )
        // Streaming already above the cap (should not happen after resolve):
        // offline never goes looser than streaming, so it stays at the base.
        assertEquals(
            0.88f,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.88f, 2),
            0.0001f,
        )
        assertEquals(
            OpenWakeWordCutoffPolicy.EXTRA_VERIFY_VERIFIER_CAP,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.65f, 2, hasBuiltInVerifier = true),
            0.0001f,
        )
        assertTrue(OpenWakeWordCutoffPolicy.extraStrictnessAllowUnverified(1))
        assertFalse(OpenWakeWordCutoffPolicy.extraStrictnessAllowUnverified(2))
        assertFalse(OpenWakeWordCutoffPolicy.extraStrictnessAllowUnverified(0))
        // Echo-reference path (level 0) must stay on the streaming bar.
        assertEquals(
            0.65f,
            OpenWakeWordCutoffPolicy.offlineVerifyCutoff(0.65f, 0, hasBuiltInVerifier = true),
            0.0001f,
        )
    }

    @Test
    fun extraStrictnessBumpsConsecutiveHitsOnly() {
        val consecutive = OpenWakeWordCutoffPolicy.GateParams(1, 2)
        assertEquals(
            3,
            OpenWakeWordCutoffPolicy.applyExtraStrictness(consecutive, false, 2).requiredHits,
        )
        // Level 1 does not change the streaming gate (offline verify is the caller's job).
        assertEquals(
            consecutive,
            OpenWakeWordCutoffPolicy.applyExtraStrictness(consecutive, false, 1),
        )
        // Verifier models stay single-spike.
        assertEquals(
            consecutive,
            OpenWakeWordCutoffPolicy.applyExtraStrictness(consecutive, true, 2),
        )
        // A model with a near-word verifier head keeps the default gate at level 2: the
        // head already delivers the rejection, hits=3 would only cost recall. Same for
        // the strict VAD rule.
        assertEquals(
            consecutive,
            OpenWakeWordCutoffPolicy.applyExtraStrictness(consecutive, false, 2, hasVerifierHead = true),
        )
        assertFalse(OpenWakeWordCutoffPolicy.strictVadFor(extraLevel = 2, hasVerifierHead = true))
        assertTrue(OpenWakeWordCutoffPolicy.strictVadFor(extraLevel = 2, hasVerifierHead = false))
        assertFalse(OpenWakeWordCutoffPolicy.strictVadFor(extraLevel = 1, hasVerifierHead = false))
        // Official hey_jarvis: same exemption as the hit bump. The AND-rule
        // window average fails a two-syllable spike and was stacking on top of
        // the built-in ONNX verifier for no extra rejection.
        assertFalse(
            OpenWakeWordCutoffPolicy.strictVadFor(
                extraLevel = 2,
                hasVerifierHead = false,
                hasBuiltInVerifier = true,
            ),
        )
        // An author-tuned averaging window is left alone.
        val windowed = OpenWakeWordCutoffPolicy.GateParams(3, 1)
        assertEquals(
            windowed,
            OpenWakeWordCutoffPolicy.applyExtraStrictness(windowed, false, 2),
        )
        // Already-stricter author hits stay as written.
        val already = OpenWakeWordCutoffPolicy.GateParams(1, 4)
        assertEquals(
            4,
            OpenWakeWordCutoffPolicy.applyExtraStrictness(already, false, 2).requiredHits,
        )
    }

    @Test
    fun homeAssistantCatalogLabelMatchesTheActualModelPhrase() {
        assertEquals(
            "Home Assistant",
            OpenWakeWordModel.correctedDisplayName("hey_home_assistant", "Hey Home Assistant"),
        )
        assertEquals(
            "Hey Jarvis",
            OpenWakeWordModel.correctedDisplayName("hey_jarvis", "Hey Jarvis"),
        )
    }

    @Test
    fun verifierVetoTightensWithEachExtraStrictnessLevelAndNeverDisables() {
        val l0 = OpenWakeWordCutoffPolicy.verifierThreshold(0)
        val l1 = OpenWakeWordCutoffPolicy.verifierThreshold(1)
        val l2 = OpenWakeWordCutoffPolicy.verifierThreshold(2)
        // The veto is the precision layer at every level, so even level 0 keeps it on.
        assertTrue(l0 > 0f)
        assertTrue(l0 < l1 && l1 < l2)
        // Measured on the bundled head: 0.85 still costs zero genuine wakes across the
        // 1,056-clip matrix and eight never-seen speakers, so nothing above it is
        // needed and anything above would only be untested.
        assertTrue(l2 <= 0.85f)
        assertEquals(l2, OpenWakeWordCutoffPolicy.verifierThreshold(5), 0f)
    }

    @Test
    fun alternatePathsDefaultOnAndOnlyExplicitFalseDisablesThem() {
        // Imports and legacy manifests (no key) keep the tempo / silence-tail paths.
        assertTrue(OpenWakeWordManifest.wrapOnnx("community", "Community").alternatePaths)
        val legacy = OpenWakeWordManifest.fromJson(
            """{"type":"openwakeword","format":"openwakeword-v1","id":"legacy","wake_word":"Legacy"}""",
        )!!
        assertTrue(legacy.alternatePaths)

        val confined = OpenWakeWordManifest.wrapOnnx(
            id = "confined",
            wakeWord = "Confined",
            alternatePaths = false,
        )
        val restored = OpenWakeWordManifest.fromJson(
            OpenWakeWordManifest.toJsonObject(confined).toString(),
        )!!
        assertFalse(restored.alternatePaths)
    }
}
