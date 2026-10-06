package com.example.ava.lyrics

import android.content.Context
import android.util.Log
import com.example.ava.net.GithubProxyUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Community AMLL TTML word-sync lyrics ([amll-ttml-db](https://github.com/amll-dev/amll-ttml-db)).
 * Lookup by QQ Music song mid after our QQ matcher resolves a candidate.
 */
class AmllLyricsClient(
    private val httpClient: OkHttpClient = sharedClient,
) {
    suspend fun fetchWordSyncedByQqMid(
        qqMid: String,
        durationMs: Long = 0L,
        context: Context? = null,
    ): List<LrcLine>? = withContext(Dispatchers.IO) {
        val mid = qqMid.trim()
        if (mid.isEmpty()) return@withContext null
        try {
            val directUrl = "$QQ_TTML_BASE/$mid.ttml"
            val ttml = fetchTtml(context, directUrl) ?: return@withContext null
            val lines = TtmlParser.parse(ttml).filter { it.isWordSynced && it.text.isNotBlank() }
            if (lines.isEmpty()) {
                Log.i(TAG, "AMLL TTML for qq mid=$mid has no word spans")
                return@withContext null
            }
            if (LrcParser.isInstrumentalOnly(lines.map { LrcLine(it.timeMs, it.text) })) {
                return@withContext null
            }
            if (durationMs > 0L && !LrcParser.fitsTrackDuration(lines, durationMs)) {
                Log.i(
                    TAG,
                    "AMLL qq mid=$mid rejected by duration " +
                        "(track=${durationMs}ms last=${LrcParser.lastTimedMs(lines)}ms)",
                )
                return@withContext null
            }
            Log.i(TAG, "AMLL word-sync hit qq mid=$mid (${lines.size} lines)")
            lines
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AMLL fetch failed for qq mid=$mid", e)
            null
        }
    }

    private fun fetchTtml(context: Context?, directUrl: String): String? {
        for (url in GithubProxyUrls.candidates(context, directUrl)) {
            val body = fetchTtmlOnce(url) ?: continue
            if (url != directUrl) {
                Log.i(TAG, "AMLL TTML via mirror: $url")
            }
            return body
        }
        return null
    }

    private fun fetchTtmlOnce(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Ava/1.0 (Android; +https://github.com/amll-dev/amll-ttml-db)",
            )
            .get()
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> null
                    else -> response.body?.string()?.takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AmllLyricsClient"
        private const val QQ_TTML_BASE =
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/qq-lyrics"

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
