package com.example.ava.update

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Tracks the SHA-256 of APKs installed through Ava's updater.
 *
 * Important: hashing [ApplicationInfo.sourceDir] is NOT a reliable identity for
 * GitHub release assets — many sideload / OEM / rebuild paths produce a different
 * on-disk APK than the uploaded file even when versionCode matches. Using that
 * hash as the update baseline caused perpetual "update available" prompts.
 *
 * Same-version rebuild detection only uses a SHA written by [markInstalled]
 * / [markStableJsonSha] before package replace (process death would otherwise
 * leave the previous rebuild as the HA "Installed" suffix).
 */
object UpdateArtifactStore {
    private const val TAG = "UpdateArtifactStore"
    private const val PREFS = "ava_update_artifact"
    private const val KEY_SHA256 = "installed_apk_sha256"
    private const val KEY_ASSET = "installed_apk_asset"
    private const val KEY_VERSION = "installed_apk_version"
    /** Publisher / downloaded APK length in bytes (not a live sourceDir hash). */
    private const val KEY_SIZE = "installed_apk_size"
    /** Last acknowledged version.json publisher digest (auto-update only; never APK file hash). */
    private const val KEY_STABLE_JSON_SHA = "stable_json_sha256"
    private const val KEY_STABLE_JSON_VERSION = "stable_json_version_name"
    private const val KEY_STABLE_JSON_SIZE = "stable_json_size"
    /**
     * Bump when tracker semantics change. v2 = publisher digest only
     * (invalidates sourceDir seeds and legacy file-hash trackers).
     */
    private const val KEY_TRACKER_SCHEMA = "tracker_schema"
    private const val TRACKER_SCHEMA_PUBLISHER = 2

    /**
     * Legacy marker written when we used to seed prefs from sourceDir.
     * Those values must not drive update availability.
     */
    private const val ASSET_SEEDED_FROM_INSTALL = "installed-base.apk"

    /** Software-channel auto prompt: user tapped "Later" for this stable candidate. */
    private const val KEY_SKIPPED_PROMPT_VERSION = "skipped_software_prompt_version"
    private const val KEY_SKIPPED_PROMPT_CODE = "skipped_software_prompt_code"
    private const val KEY_SKIPPED_PROMPT_SHA = "skipped_software_prompt_sha"

    fun normalizeSha256(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .removePrefix("sha256:")
            .removePrefix("SHA256:")
            .lowercase()
            .filter { it in '0'..'9' || it in 'a'..'f' }
    }

    /** True when [raw] normalizes to a full 64-char hex digest. */
    fun isValidSha256(raw: String?): Boolean = normalizeSha256(raw).length == 64

    /** True when version.json includes a publisher sha256 (whole field present and valid). */
    fun jsonIncludesPublisherSha(remoteSha256: String?): Boolean =
        isValidSha256(normalizeSha256(remoteSha256))

    /**
     * Software / auto-update: last stored version.json publisher sha (not on-disk APK hash).
     */
    fun getStableJsonSha256(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_STABLE_JSON_SHA, null)
            ?.let { normalizeSha256(it) }
            ?.takeIf { it.length == 64 }
    }

    fun markStableJsonSha(
        context: Context,
        sha256: String,
        versionName: String = "",
        sizeBytes: Long = 0,
    ) {
        val norm = normalizeSha256(sha256)
        if (norm.length != 64) return
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STABLE_JSON_SHA, norm)
            .putString(KEY_STABLE_JSON_VERSION, versionName.trim())
        if (sizeBytes > 0L) editor.putLong(KEY_STABLE_JSON_SIZE, sizeBytes)
        val ok = editor.commit()
        Log.i(
            TAG,
            "Stable json sha tracked sha256=${norm.take(16)}… version=$versionName " +
                "size=$sizeBytes commit=$ok",
        )
    }

    fun clearStableJsonShaIfMatches(context: Context, sha256: String) {
        val norm = normalizeSha256(sha256)
        if (norm.length != 64) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_STABLE_JSON_SHA, null)?.let { normalizeSha256(it) } ?: return
        if (!current.equals(norm, ignoreCase = true)) return
        prefs.edit()
            .remove(KEY_STABLE_JSON_SHA)
            .remove(KEY_STABLE_JSON_VERSION)
            .remove(KEY_STABLE_JSON_SIZE)
            .apply()
        Log.i(TAG, "Cleared stable json sha after failed install sha256=${norm.take(16)}…")
    }

    /** Drop stable json sha tracking (version.json release without sha256 / version-only install). */
    fun clearStableJsonSha(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STABLE_JSON_SHA)
            .remove(KEY_STABLE_JSON_VERSION)
            .remove(KEY_STABLE_JSON_SIZE)
            .apply()
        Log.i(TAG, "Cleared stable json sha (versionCode-only mode)")
    }

    /**
     * SHA recorded by the updater (written before package replace so it
     * survives process death). Runs a one-time schema migration that drops
     * pre-publisher trackers (sourceDir seeds and file-hash baselines).
     */
    fun getTrackedArtifactSha256(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateTrackerSchemaIfNeeded(prefs)
        val asset = prefs.getString(KEY_ASSET, null).orEmpty()
        if (asset == ASSET_SEEDED_FROM_INSTALL) {
            clearTrackedArtifact(prefs, reason = "sourceDir-seeded baseline")
            return null
        }
        return prefs.getString(KEY_SHA256, null)
            ?.let { normalizeSha256(it) }
            ?.takeIf { it.length == 64 }
    }

    private fun migrateTrackerSchemaIfNeeded(prefs: android.content.SharedPreferences) {
        val schema = prefs.getInt(KEY_TRACKER_SCHEMA, 0)
        if (schema >= TRACKER_SCHEMA_PUBLISHER) return
        // Drop any prior baseline — only markInstalled() after this build re-creates it
        // from version.json / GitHub publisher digests.
        val had = prefs.contains(KEY_SHA256)
        prefs.edit()
            .remove(KEY_SHA256)
            .remove(KEY_ASSET)
            .remove(KEY_VERSION)
            .remove(KEY_SIZE)
            .putInt(KEY_TRACKER_SCHEMA, TRACKER_SCHEMA_PUBLISHER)
            .apply()
        if (had) {
            Log.i(TAG, "Migrated update tracker to schema $TRACKER_SCHEMA_PUBLISHER (cleared legacy sha)")
        }
    }

    private fun clearTrackedArtifact(prefs: android.content.SharedPreferences, reason: String) {
        prefs.edit()
            .remove(KEY_SHA256)
            .remove(KEY_ASSET)
            .remove(KEY_VERSION)
            .remove(KEY_SIZE)
            .apply()
        Log.i(TAG, "Cleared tracked artifact ($reason)")
    }

    @Deprecated("Use getTrackedArtifactSha256", ReplaceWith("getTrackedArtifactSha256(context)"))
    fun getInstalledSha256(context: Context): String? = getTrackedArtifactSha256(context)

    /**
     * Publisher digest for UI / HA labels.
     *
     * Prefer [getStableJsonSha256]: it is written *before* package replace so it
     * survives process death. [getTrackedArtifactSha256] is often still the
     * previous APK when silent install kills the process before [markInstalled].
     * Never hashes [ApplicationInfo.sourceDir] — that produced mismatched
     * Installed/Latest suffixes (e.g. APK bytes vs publisher digest).
     */
    fun getPublisherBaselineSha256(context: Context): String? =
        getStableJsonSha256(context) ?: getTrackedArtifactSha256(context)

    /**
     * True when [remoteSha256] matches either persisted publisher digest.
     * The two keys can diverge after a same-version rebuild: stable_json is
     * committed before install, tracked may remain the previous artifact.
     */
    fun matchesPublisherBaseline(context: Context, remoteSha256: String?): Boolean {
        val remote = normalizeSha256(remoteSha256)
        if (remote.length != 64) return false
        val json = getStableJsonSha256(context)
        if (remote.equals(json, ignoreCase = true)) return true
        val tracked = getTrackedArtifactSha256(context)
        return remote.equals(tracked, ignoreCase = true)
    }

    /**
     * Cheap same-artifact check: publisher/download length, or a `stat()` of the
     * installed base APK. This is a metadata read (microseconds) — it does **not**
     * open or hash the 18 MB file, so it cannot hitch the UI.
     *
     * Size match means "already on this package" (heals a stale SHA after silent
     * replace). Size mismatch alone is never treated as an update — OEM copies
     * can differ by a few bytes from the GitHub asset.
     */
    fun matchesPublisherSize(context: Context, remoteSizeBytes: Long): Boolean {
        if (remoteSizeBytes <= 0L) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jsonSize = prefs.getLong(KEY_STABLE_JSON_SIZE, 0L)
        if (jsonSize == remoteSizeBytes) return true
        val trackedSize = prefs.getLong(KEY_SIZE, 0L)
        if (trackedSize == remoteSizeBytes) return true
        val live = installedApkLength(context)
        return live > 0L && live == remoteSizeBytes
    }

    /** `stat()` of the installed base APK — inode metadata only, no content I/O. */
    fun installedApkLength(context: Context): Long = runCatching {
        val ai = context.applicationInfo
        val path = ai.publicSourceDir?.takeIf { it.isNotBlank() } ?: ai.sourceDir
        val file = File(path)
        if (file.isFile) file.length() else 0L
    }.getOrDefault(0L)

    /**
     * Best-effort identity for diagnostics only: publisher baseline first,
     * otherwise a live hash of the installed base APK (never persisted).
     */
    fun resolveBaselineSha256(context: Context): String? {
        getPublisherBaselineSha256(context)?.let { return it }
        return hashInstalledApk(context)
    }

    fun markInstalled(
        context: Context,
        sha256: String,
        assetName: String = "",
        versionName: String = "",
        sizeBytes: Long = 0,
    ) {
        val norm = normalizeSha256(sha256)
        if (norm.length != 64) return
        // Never persist the untrusted seed marker as a "tracked" install.
        val asset = assetName.trim().ifEmpty { "updater.apk" }
        if (asset == ASSET_SEEDED_FROM_INSTALL) {
            Log.w(TAG, "Refusing to markInstalled with seeded asset marker")
            return
        }
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SHA256, norm)
            .putString(KEY_ASSET, asset)
            .putString(KEY_VERSION, versionName)
            .putInt(KEY_TRACKER_SCHEMA, TRACKER_SCHEMA_PUBLISHER)
        if (sizeBytes > 0L) editor.putLong(KEY_SIZE, sizeBytes)
        val ok = editor.commit()
        Log.i(
            TAG,
            "Tracked installed artifact sha256=${norm.take(16)}… asset=$asset " +
                "size=$sizeBytes commit=$ok",
        )
    }

    /** Undo [markInstalled] when install failed after a pre-install mark. */
    fun clearTrackedIfPublisherSha(context: Context, sha256: String) {
        val norm = normalizeSha256(sha256)
        if (norm.length != 64) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_SHA256, null)?.let { normalizeSha256(it) } ?: return
        if (!current.equals(norm, ignoreCase = true)) return
        prefs.edit()
            .remove(KEY_SHA256)
            .remove(KEY_ASSET)
            .remove(KEY_VERSION)
            .remove(KEY_SIZE)
            .apply()
        Log.i(TAG, "Cleared tracked artifact after failed install sha256=${norm.take(16)}…")
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun hashInstalledApk(context: Context): String? = runCatching {
        val ai = context.applicationInfo
        val path = ai.publicSourceDir?.takeIf { it.isNotBlank() } ?: ai.sourceDir
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "Installed APK path not a file: $path")
            return@runCatching null
        }
        sha256Hex(file)
    }.onFailure {
        Log.w(TAG, "Hash installed APK failed: ${it.message}")
    }.getOrNull()?.takeIf { it.length == 64 }

    /**
     * Software-channel availability — **version.json is the source of truth**.
     *
     * - remote versionCode &gt; installed → update
     * - remote versionCode &lt; installed → never
     * - same versionCode and version.json **has no** sha256 field (blank / invalid) →
     *   **versionCode only** (already on this release → no update)
     * - same versionCode and version.json **has** sha256 → update only when a
     *   stored publisher digest exists and differs (never uses on-disk APK hash;
     *   a missing baseline is not an update)
     * - same versionCode and remote size matches stored or live APK length →
     *   already on this package (size is confirm-only, never an update trigger)
     */
    fun isUpdateAvailable(
        context: Context,
        remoteVersionCode: Int,
        remoteSha256: String?,
        installedVersionCode: Int,
        remoteSizeBytes: Long = 0,
    ): Boolean {
        if (remoteVersionCode > 0 && remoteVersionCode < installedVersionCode) {
            return false
        }
        if (remoteVersionCode > installedVersionCode) {
            return true
        }
        if (!jsonIncludesPublisherSha(remoteSha256)) {
            return false
        }
        if (matchesPublisherBaseline(context, remoteSha256)) {
            return false
        }
        if (matchesPublisherSize(context, remoteSizeBytes)) {
            return false
        }
        // Same versionCode + publisher sha, but no local baseline: already on
        // this version number. Do not nag — a rebuild is only detectable after
        // a previous updater-tracked install. `stored == null` used to return
        // true and caused perpetual reminders after silent package replace.
        val stored = getStableJsonSha256(context) ?: getTrackedArtifactSha256(context)
        return stored != null
    }

    /** Remember a stable candidate so the software auto-dialog won't nag again. */
    fun skipSoftwarePrompt(context: Context, versionName: String, versionCode: Int, sha256: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SKIPPED_PROMPT_VERSION, versionName.trim())
            .putInt(KEY_SKIPPED_PROMPT_CODE, versionCode)
            .putString(KEY_SKIPPED_PROMPT_SHA, normalizeSha256(sha256).takeIf { it.length == 64 }.orEmpty())
            .apply()
        Log.i(TAG, "Software prompt skipped for $versionName ($versionCode)")
    }

    /**
     * True when [remote] is the same artifact the user already dismissed with "Later".
     * A newer versionName / versionCode / different sha clears the match automatically.
     */
    fun isSoftwarePromptSkipped(
        context: Context,
        versionName: String,
        versionCode: Int,
        sha256: String?,
    ): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val skippedVersion = prefs.getString(KEY_SKIPPED_PROMPT_VERSION, null)?.trim().orEmpty()
        if (skippedVersion.isEmpty()) return false
        val skippedCode = prefs.getInt(KEY_SKIPPED_PROMPT_CODE, -1)
        val skippedSha = normalizeSha256(prefs.getString(KEY_SKIPPED_PROMPT_SHA, null))
        val remoteSha = normalizeSha256(sha256)

        if (!jsonIncludesPublisherSha(sha256)) {
            return versionName.trim() == skippedVersion &&
                (versionCode == 0 || skippedCode <= 0 || versionCode == skippedCode)
        }

        // Same publisher digest already dismissed.
        if (remoteSha.length == 64 && skippedSha.length == 64) {
            return remoteSha.equals(skippedSha, ignoreCase = true)
        }
        // Skipped a sha-less candidate, then version.json gained sha256 (rebuild) → prompt again.
        if (remoteSha.length == 64 && skippedSha.isEmpty()) {
            return false
        }
        return versionName.trim() == skippedVersion &&
            (versionCode == 0 || skippedCode <= 0 || versionCode == skippedCode)
    }
}
