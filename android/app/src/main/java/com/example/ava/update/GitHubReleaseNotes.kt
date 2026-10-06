package com.example.ava.update

import android.content.Context
import android.util.Log
import com.example.ava.net.GithubFetchGuard
import com.example.ava.net.GithubProxyUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub releases for HA update-entity notes + beta/pre-release installs.
 *
 * Software / in-app stable updates use version.json only (see [AppUpdater]);
 * this class must not decide stable "update available" by hashing device APKs.
 */
object GitHubReleaseNotes {
    private const val TAG = "GitHubReleaseNotes"
    private const val REPO = "knoop7/Ava"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=20"

    /**
     * One-release probe used as a cheap freshness gate before pulling the full
     * list. GitHub rate limits made blind periodic full fetches too expensive;
     * a single-item request with ETag costs nothing when unchanged (304 is not
     * counted against the limit) and detects a new publish immediately.
     */
    private const val RELEASES_PROBE_API = "https://api.github.com/repos/$REPO/releases?per_page=1"
    private const val PROBE_PREFS = "ava_release_probe"
    private const val KEY_PROBE_ETAG = "etag"
    private const val KEY_PROBE_FINGERPRINT = "fingerprint"
    private const val KEY_PROBE_AT_MS = "probe_at_ms"
    /** Collapse probe bursts (launch entity check + settings entry) into one request. */
    private const val PROBE_MIN_INTERVAL_MS = 3L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        /** GitHub form: `sha256:77ff6631…` */
        val digest: String = "",
        val size: Long = 0,
    ) {
        val sha256Hex: String
            get() = UpdateArtifactStore.normalizeSha256(digest)
    }

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        @SerialName("published_at") val publishedAt: String = "",
        val assets: List<Asset> = emptyList(),
    ) {
        val displayName: String
            get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: tagName

        val fullBody: String
            get() = body.orEmpty().trim()

        fun isBetaChannel(): Boolean {
            if (prerelease) return true
            val hay = "$tagName ${name.orEmpty()}".lowercase()
            return BETA_MARKERS.any { it in hay }
        }

        fun apkAsset(): Asset? = assets.firstOrNull { asset ->
            val n = asset.name.lowercase()
            n.endsWith(".apk") && "gecko" !in n && asset.browserDownloadUrl.isNotBlank()
        }
    }

    data class EntityNotes(
        val entityTarget: Release?,
        val stable: Release?,
        val beta: Release?,
        val softwareStableVersion: String,
        /** True when GitHub target APK sha256 differs from last installed artifact. */
        val entityHasUpdate: Boolean,
        /**
         * Digest appended to Latest for HA string compare — GitHub asset digest
         * (entity installs that APK). Must differ from current when [entityHasUpdate].
         */
        val latestPublisherSha: String = "",
    ) {
        fun releaseUrl(): String =
            entityTarget?.htmlUrl?.takeIf { it.isNotEmpty() }
                ?: stable?.htmlUrl?.takeIf { it.isNotEmpty() }
                ?: "https://github.com/$REPO/releases"

        /**
         * HA only toggles "update available" by comparing version *strings*.
         * When sha256 differs but tag equals installed version, append a short
         * build id so HA does not treat it as up-to-date.
         */
        fun entityLatestLabel(
            installedVersionName: String,
            latestPublisherSha: String? = null,
        ): String {
            val target = entityTarget ?: return installedVersionName
            if (!entityHasUpdate) return installedVersionName
            val tag = target.tagName.ifBlank { target.displayName }
            val sha = UpdateArtifactStore.normalizeSha256(latestPublisherSha)
                .takeIf { it.length == 64 }
                ?: target.apkAsset()?.sha256Hex.orEmpty()
            return if (sha.length >= 8) "$tag.${sha.take(8)}" else tag
        }

        fun entityCurrentLabel(installedVersionName: String, baselineSha: String?): String {
            if (!entityHasUpdate) return installedVersionName
            val sha = UpdateArtifactStore.normalizeSha256(baselineSha)
            return if (sha.length >= 8) "$installedVersionName.${sha.take(8)}" else installedVersionName
        }

        /**
         * Latest install target (usually pre-release) first, then stable notes.
         * Entity still installs [entityTarget] only.
         */
        fun formatSummary(installTip: String = ""): String = buildString {
            fun appendRelease(release: Release) {
                if (release.htmlUrl.isNotEmpty()) {
                    appendLine(release.htmlUrl)
                    appendLine()
                }
                val body = release.fullBody
                if (body.isNotEmpty()) appendLine(body)
                else appendLine("_No release notes on GitHub for this tag._")
            }

            when {
                entityTarget != null -> {
                    appendRelease(entityTarget)
                    val stableRelease = stable
                    if (stableRelease != null &&
                        stableRelease.tagName != entityTarget.tagName
                    ) {
                        appendLine()
                        appendLine("---")
                        appendLine()
                        appendRelease(stableRelease)
                    }
                }
                stable != null -> appendRelease(stable)
                else -> appendLine("_No installable GitHub APK found._")
            }

            if (installTip.isNotBlank()) {
                appendLine()
                appendLine("---")
                appendLine(installTip)
            }
        }.trim()
    }

    suspend fun fetchForEntity(
        context: Context,
        softwareStableVersion: String,
        installedVersionName: String,
        installedVersionCode: Int,
        softwareStableSha256: String? = null,
        forceRefresh: Boolean = false,
    ): EntityNotes? = withContext(Dispatchers.IO) {
        val releases = fetchReleases(
            context = context,
            preferCache = !forceRefresh,
            cacheTtlMs = ENTITY_CACHE_TTL_MS,
            forceRefresh = forceRefresh,
        ) ?: return@withContext null
        val published = releases.filter { !it.draft }
        val stables = published.filter { !it.isBetaChannel() }
        val betas = published.filter { it.isBetaChannel() }

        val targetNorm = normalizeVersion(softwareStableVersion)
        val stable = stables.firstOrNull { normalizeVersion(it.tagName) == targetNorm }
            ?: stables.firstOrNull { normalizeVersion(it.displayName) == targetNorm }
            ?: stables.firstOrNull()

        // Entity installs newest pre-release APK when available; otherwise newest non-older APK.
        val entityTarget = betas.firstOrNull { release ->
            release.apkAsset() != null && !isClearlyOlder(release.tagName, installedVersionName)
        } ?: published.firstOrNull { release ->
            release.apkAsset() != null && !isClearlyOlder(release.tagName, installedVersionName)
        }
        val asset = entityTarget?.apkAsset()
        val githubSha = UpdateArtifactStore.normalizeSha256(asset?.sha256Hex)
        val jsonSha = UpdateArtifactStore.normalizeSha256(softwareStableSha256)
        val sameStableTuple = entityTarget != null &&
            UpdateArtifactStore.isValidSha256(jsonSha) &&
            normalizeVersion(entityTarget.tagName) == targetNorm
        val storedSha = UpdateArtifactStore.getPublisherBaselineSha256(context)
        // Entity installs the GitHub APK. Match against *either* persisted
        // digest: stable_json is written before package replace, tracked may
        // still be the previous rebuild (e.g. 20e5cf77 vs bd21eaa1).
        // A missing baseline on the same tag is not an update — that used to
        // leave HA "on" forever after silent install killed the process.
        val githubMatches = UpdateArtifactStore.matchesPublisherBaseline(context, githubSha)
        val jsonMatches = sameStableTuple &&
            UpdateArtifactStore.matchesPublisherBaseline(context, jsonSha)
        val sizeMatches = UpdateArtifactStore.matchesPublisherSize(context, asset?.size ?: 0L)
        val hasBaseline = storedSha != null
        val githubDiffers = githubSha.length == 64 && hasBaseline && !githubMatches && !sizeMatches
        val jsonDiffers = sameStableTuple && hasBaseline && !jsonMatches && !sizeMatches
        val entityHasUpdate = when {
            asset == null || entityTarget == null -> false
            isClearlyOlder(entityTarget.tagName, installedVersionName) -> false
            isClearlyNewer(entityTarget.tagName, installedVersionName) -> true
            githubMatches || sizeMatches -> false
            githubDiffers || jsonDiffers -> true
            else -> false
        }
        // Latest suffix must use a digest that differs from stored, or HA hides Install.
        val latestSha = when {
            githubSha.length == 64 && !githubMatches -> githubSha
            jsonSha.length == 64 && !jsonMatches -> jsonSha
            githubSha.length == 64 -> githubSha
            jsonSha.length == 64 -> jsonSha
            else -> ""
        }
        Log.d(
            TAG,
            "entityHasUpdate=$entityHasUpdate installed=$installedVersionName/$installedVersionCode " +
                "target=${entityTarget?.tagName} stored=${storedSha?.take(12)} " +
                "latest=${latestSha.take(12)} json=${jsonSha.take(12)} github=${githubSha.take(12)} " +
                "sizeMatch=$sizeMatches remoteSize=${asset?.size}",
        )

        EntityNotes(
            entityTarget = entityTarget,
            stable = stable,
            beta = betas.firstOrNull(),
            softwareStableVersion = softwareStableVersion,
            entityHasUpdate = entityHasUpdate,
            latestPublisherSha = latestSha,
        )
    }

    /**
     * Optional helper for tooling / diagnostics.
     * Software update checks no longer call this — they use version.json only.
     * Never invents a sha256 when [base] has none (legacy JSON must stay blank).
     */
    suspend fun enrichStableUpdateInfo(context: Context, base: UpdateInfo): UpdateInfo =
        withContext(Dispatchers.IO) {
            val jsonSha = UpdateArtifactStore.normalizeSha256(base.sha256)
            val releases = fetchReleases(context) ?: return@withContext base
            val stables = releases.filter { !it.draft && !it.isBetaChannel() }
            val targetNorm = normalizeVersion(base.versionName)
            val baseFile = base.downloadUrl.substringAfterLast('/')
            val release = stables.firstOrNull { normalizeVersion(it.tagName) == targetNorm }
                ?: stables.firstOrNull { normalizeVersion(it.displayName) == targetNorm }
                ?: stables.firstOrNull { rel ->
                    val url = rel.apkAsset()?.browserDownloadUrl ?: return@firstOrNull false
                    base.downloadUrl.contains(rel.tagName) ||
                        url == base.downloadUrl ||
                        url.substringAfterLast('/') == baseFile
                }
            val asset = release?.apkAsset() ?: return@withContext base
            base.copy(
                // Keep JSON sha as-is (including blank for legacy). Do not fill from GitHub.
                sha256 = jsonSha,
                sizeBytes = asset.size.takeIf { it > 0 } ?: base.sizeBytes,
                assetName = asset.name.ifBlank { base.assetName },
                downloadUrl = base.downloadUrl.ifBlank { asset.browserDownloadUrl },
            )
        }

    fun toUpdateInfo(release: Release): UpdateInfo? {
        val asset = release.apkAsset() ?: return null
        return UpdateInfo(
            versionCode = 0,
            versionName = release.tagName.ifBlank { release.displayName },
            downloadUrl = asset.browserDownloadUrl,
            changelog = release.fullBody,
            forceUpdate = false,
            sha256 = asset.sha256Hex,
            sizeBytes = asset.size,
            assetName = asset.name,
        )
    }

    /** Published releases that include an Ava APK (settings picker / manual install). */
    suspend fun fetchInstallableReleases(
        context: Context,
        forceRefresh: Boolean = false,
    ): List<Release> = withContext(Dispatchers.IO) {
        fetchReleases(
            context = context,
            preferCache = !forceRefresh,
            cacheTtlMs = SETTINGS_CACHE_TTL_MS,
            forceRefresh = forceRefresh,
        )?.let { installableOf(it) }
            .orEmpty()
    }

    /** Disk copy for the settings page — no TTL. Empty when nothing has been fetched yet. */
    fun cachedInstallableReleases(context: Context): List<Release> =
        readReleasesCache(context, maxAgeMs = null)
            ?.let { installableOf(it) }
            .orEmpty()

    /**
     * Identity of the newest published release: publish date, tag, APK digest
     * and size. A newer publish changes the date/tag; a same-version rebuild
     * changes digest/size — both must invalidate the cached list.
     */
    internal fun newestFingerprint(releases: List<Release>): String {
        val newest = releases.firstOrNull { !it.draft } ?: return ""
        val asset = newest.apkAsset()
        return listOf(
            newest.publishedAt,
            newest.tagName,
            asset?.sha256Hex.orEmpty(),
            (asset?.size ?: 0L).toString(),
        ).joinToString("|")
    }

    private fun installableOf(releases: List<Release>): List<Release> =
        releases.filter { !it.draft && it.apkAsset() != null }

    /** Fallback only — used when the freshness probe cannot answer (offline / cooling). */
    private const val SETTINGS_CACHE_TTL_MS = 15L * 60 * 1000
    /** Fallback only — probe failure keeps hourly HA entity checks on disk inside this window. */
    private const val ENTITY_CACHE_TTL_MS = 6L * 60 * 60 * 1000

    private fun releasesCacheFile(context: Context) =
        java.io.File(context.cacheDir, "github_releases.json")

    private fun readReleasesCache(context: Context, maxAgeMs: Long?): List<Release>? {
        val file = releasesCacheFile(context)
        if (!file.isFile || file.length() == 0L) return null
        if (maxAgeMs != null && System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
            return null
        }
        return runCatching { json.decodeFromString<List<Release>>(file.readText()) }
            .onFailure { file.delete() }
            .getOrNull()
    }

    /**
     * zh/ru prefer proxy first; others try GitHub API direct first.
     *
     * Automatic checks ask the one-release probe whether the disk cache still
     * matches the newest publish; only a changed fingerprint pulls the full
     * list, so a release published minutes ago shows up on the next check
     * without burning rate limit on unchanged polls. When the probe cannot
     * answer, [cacheTtlMs] applies as before. A user/HA CHECK can
     * [forceRefresh]. Network walks at most two candidates and skips hosts
     * still cooling down after timeout/403. Failure always falls back to
     * whatever is on disk.
     */
    private fun fetchReleases(
        context: Context,
        preferCache: Boolean = true,
        cacheTtlMs: Long = SETTINGS_CACHE_TTL_MS,
        forceRefresh: Boolean = false,
    ): List<Release>? {
        if (preferCache) {
            val cached = readReleasesCache(context, maxAgeMs = null)
            if (cached != null) {
                when (probeSaysCacheCurrent(context, newestFingerprint(cached))) {
                    true -> return cached
                    false -> Log.i(TAG, "Probe found a newer publish; refreshing release list")
                    null -> readReleasesCache(context, cacheTtlMs)?.let { return it }
                }
            }
        }
        val urls = GithubProxyUrls.cautiousCandidates(
            context = context,
            directUrl = RELEASES_API,
            allowIfAllCooling = forceRefresh,
        )
        if (urls.isEmpty()) {
            Log.w(TAG, "GitHub releases mirrors cooling down; using disk cache")
            return readReleasesCache(context, maxAgeMs = null)
        }
        for (url in urls) {
            val text = fetchJson(url) ?: continue
            val list = runCatching { json.decodeFromString<List<Release>>(text) }.getOrNull()
            if (list != null) {
                GithubFetchGuard.rememberSuccess(url)
                runCatching { releasesCacheFile(context).writeText(text) }
                rememberProbeBaseline(context, newestFingerprint(list))
                return list
            }
            Log.w(TAG, "GitHub releases JSON invalid for $url")
        }
        return readReleasesCache(context, maxAgeMs = null)
    }

    /**
     * Sync the probe baseline after any full-list fetch (including forced
     * refreshes that bypass the probe). Without this, a stale stored
     * fingerprint inside the throttle window would flag the fresh cache as
     * outdated and trigger pointless full refetches. The stored ETag belongs
     * to the probe URL and may now be stale, so drop it — the next probe then
     * runs unconditionally and re-establishes it.
     */
    private fun rememberProbeBaseline(context: Context, fingerprint: String) {
        if (fingerprint.isEmpty()) return
        context.applicationContext.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROBE_FINGERPRINT, fingerprint)
            .remove(KEY_PROBE_ETAG)
            .putLong(KEY_PROBE_AT_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * @return true — cache matches the newest publish; false — a newer publish
     * (or same-tag rebuild) exists; null — probe could not answer (offline,
     * mirrors cooling, invalid JSON), caller falls back to TTL behavior.
     */
    private fun probeSaysCacheCurrent(context: Context, cachedFingerprint: String): Boolean? {
        if (cachedFingerprint.isEmpty()) return false
        val prefs = context.applicationContext
            .getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val storedFingerprint = prefs.getString(KEY_PROBE_FINGERPRINT, null).orEmpty()
        val sinceLastProbe = now - prefs.getLong(KEY_PROBE_AT_MS, 0L)
        if (storedFingerprint.isNotEmpty() && sinceLastProbe in 0 until PROBE_MIN_INTERVAL_MS) {
            return storedFingerprint == cachedFingerprint
        }

        val urls = GithubProxyUrls.cautiousCandidates(context, RELEASES_PROBE_API)
        if (urls.isEmpty()) return null
        val etag = prefs.getString(KEY_PROBE_ETAG, null)
        for (url in urls) {
            val response = probeHttp(url, etag) ?: continue
            GithubFetchGuard.rememberSuccess(url)
            if (response.notModified) {
                // Remote still matches our stored fingerprint (that is what the ETag proves).
                if (storedFingerprint.isEmpty()) return null
                prefs.edit().putLong(KEY_PROBE_AT_MS, now).apply()
                return storedFingerprint == cachedFingerprint
            }
            val fingerprint = response.body
                ?.let { body -> runCatching { json.decodeFromString<List<Release>>(body) }.getOrNull() }
                ?.let { newestFingerprint(it) }
                .orEmpty()
            if (fingerprint.isEmpty()) {
                Log.w(TAG, "Probe JSON invalid for $url")
                continue
            }
            prefs.edit()
                .putString(KEY_PROBE_FINGERPRINT, fingerprint)
                .putString(KEY_PROBE_ETAG, response.etag)
                .putLong(KEY_PROBE_AT_MS, now)
                .apply()
            return fingerprint == cachedFingerprint
        }
        return null
    }

    private data class ProbeResponse(
        val notModified: Boolean,
        val body: String?,
        val etag: String,
    )

    /** Single tiny GET with If-None-Match; null on any failure (host cooldown recorded). */
    private fun probeHttp(urlString: String, etag: String?): ProbeResponse? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Ava-UpdateProbe")
                if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
                instanceFollowRedirects = true
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> ProbeResponse(
                    notModified = true,
                    body = null,
                    etag = etag.orEmpty(),
                )
                in 200..299 -> ProbeResponse(
                    notModified = false,
                    body = connection.inputStream.bufferedReader().use { it.readText() },
                    etag = connection.getHeaderField("ETag").orEmpty(),
                )
                else -> {
                    GithubFetchGuard.rememberFailure(urlString)
                    Log.w(TAG, "Probe HTTP $code for $urlString")
                    null
                }
            }
        } catch (e: Exception) {
            GithubFetchGuard.rememberFailure(urlString)
            Log.w(TAG, "Probe failed: $urlString (${e.message})")
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun assetUrlCandidates(context: Context, url: String): List<String> =
        GithubProxyUrls.cautiousCandidates(context, url)

    /** Raw response body so callers can persist the exact text to the disk cache. */
    private fun fetchJson(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Ava-UpdateEntity")
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                GithubFetchGuard.rememberFailure(urlString)
                Log.w(TAG, "HTTP $code for $urlString")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            GithubFetchGuard.rememberFailure(urlString)
            Log.w(TAG, "Fetch failed: $urlString (${e.message})")
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun isClearlyOlder(candidate: String, installed: String): Boolean {
        val cmp = compareVersionTuples(candidate, installed) ?: return false
        return cmp < 0
    }

    fun isClearlyNewer(candidate: String, installed: String): Boolean {
        val cmp = compareVersionTuples(candidate, installed) ?: return false
        return cmp > 0
    }

    /** True when numeric version tuples match (e.g. `0.6.8` == `0.6.8.0`). */
    fun isSameVersionTuple(candidate: String, installed: String): Boolean =
        compareVersionTuples(candidate, installed) == 0

    /**
     * Same-version rebuild gate for the settings picker — mirrors the entity /
     * software-channel SHA check: GitHub APK digest vs last tracked publisher
     * baseline. No remote digest → cannot claim an upgrade (caller shows reinstall).
     */
    fun sameVersionPublisherUpdateAvailable(context: Context, release: Release): Boolean {
        val remoteSha = UpdateArtifactStore.normalizeSha256(release.apkAsset()?.sha256Hex)
        if (remoteSha.length != 64) return false
        if (UpdateArtifactStore.matchesPublisherBaseline(context, remoteSha)) return false
        if (UpdateArtifactStore.matchesPublisherSize(context, release.apkAsset()?.size ?: 0L)) {
            return false
        }
        // No baseline → reinstall current, do not claim a same-version upgrade.
        return UpdateArtifactStore.getPublisherBaselineSha256(context) != null
    }

    /** @return negative if candidate < installed, 0 if equal, positive if newer; null if unparsable. */
    private fun compareVersionTuples(candidate: String, installed: String): Int? {
        val a = versionTuple(candidate) ?: return null
        val b = versionTuple(installed) ?: return null
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun versionTuple(raw: String): List<Int>? {
        val norm = normalizeVersion(raw)
        val parts = Regex("""\d+""").findAll(norm).map { it.value.toInt() }.toList()
        return parts.takeIf { it.isNotEmpty() }
    }

    fun normalizeVersion(raw: String): String =
        raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .lowercase()
            .replace(Regex("""\b(beta|bata|alpha|rc|pre)[.\-\s]*""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun formatBytes(size: Long): String {
        val mb = size / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }

    private val BETA_MARKERS = listOf("beta", "bata", "alpha", "rc", "pre-release", "prerelease")
}
