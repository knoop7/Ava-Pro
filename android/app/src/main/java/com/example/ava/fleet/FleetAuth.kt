package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.settings.ExperimentalSettingsStore
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Fleet HTTP auth — password from DataStore (`experimental.clusterAccessToken`),
 * default plain `1234` / wire `MTIzNAAAAAAAAAAA` when unset.
 *
 * Browser may present plain password or the 16-char Base64 wire token.
 */
object FleetAuth {
    private const val TAG = "FleetAuth"
    private const val HEADER = "x-ava-fleet-token"
    private const val ALT_HEADER = "authorization"

    private val cachedWire = AtomicReference<String?>(null)

    fun headerName(): String = "X-Ava-Fleet-Token"

    fun defaultPassword(): String = FleetPasswordCodec.PLAIN_DEFAULT

    fun defaultWireToken(): String = FleetPasswordCodec.TOKEN_DEFAULT

    /** Current wire token from settings (cached). */
    fun configuredToken(context: Context): String {
        cachedWire.get()?.let { return it }
        val raw = runCatching {
            runBlocking { ExperimentalSettingsStore(context.applicationContext).get().clusterAccessToken }
        }.getOrNull().orEmpty()
        val wire = if (raw.isBlank()) {
            FleetPasswordCodec.TOKEN_DEFAULT
        } else {
            FleetPasswordCodec.toWireToken(raw)
        }
        cachedWire.set(wire)
        return wire
    }

    /** Plain password decoded from the stored wire token (for deep links). */
    fun configuredPlain(context: Context): String {
        val plain = FleetPasswordCodec.decode(configuredToken(context))
        return plain.ifEmpty { FleetPasswordCodec.PLAIN_DEFAULT }
    }

    fun invalidateCache() {
        cachedWire.set(null)
    }

    fun authRequired(context: Context): Boolean = true

    fun statusJson(context: Context) = org.json.JSONObject()
        .put("required", true)
        .put("header", headerName())
        .put("query", "password")
        .put("scheme", "password_b64_16")
        .put("defaultPassword", configuredToken(context) == FleetPasswordCodec.TOKEN_DEFAULT)

    fun extractPresented(headers: Map<String, String>, query: Map<String, String>): String {
        // Headers are not URI-encoded — do not URLDecoder (would turn '+' into space).
        headers[HEADER]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val auth = headers[ALT_HEADER]?.trim().orEmpty()
        if (auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        if (auth.startsWith("Token ", ignoreCase = true)) {
            return auth.substring(6).trim()
        }
        query["password"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return urlDecode(it) }
        query["token"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return urlDecode(it) }
        query["fleetToken"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return urlDecode(it) }
        return ""
    }

    fun check(
        context: Context,
        headers: Map<String, String>,
        query: Map<String, String> = emptyMap(),
    ): AuthResult {
        val presented = extractPresented(headers, query)
        if (presented.isEmpty()) {
            Log.w(TAG, "fleet auth denied: missing_token")
            return AuthResult.Denied("missing_token")
        }
        if (matchesPassword(context, presented)) return AuthResult.Ok(required = true)
        Log.w(TAG, "fleet auth denied: bad_token (len=${presented.length})")
        return AuthResult.Denied("bad_token")
    }

    fun matchesPassword(context: Context, presented: String): Boolean {
        val p = presented.trim()
        if (p.isEmpty()) return false
        val wire = configuredToken(context)
        if (constantTimeEquals(wire, p)) return true
        if (constantTimeEquals(wire, FleetPasswordCodec.toWireToken(p))) return true
        val plain = FleetPasswordCodec.decode(wire)
        return plain.isNotEmpty() && constantTimeEquals(plain, p)
    }

    fun generateToken(): String = defaultWireToken()

    fun ensureTokenIfClusterOn(context: Context, clusterOn: Boolean): String? {
        if (!clusterOn) return null
        return configuredToken(context)
    }

    private fun urlDecode(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            URLDecoder.decode(t, Charsets.UTF_8.name()).trim().ifEmpty { t }
        } catch (_: Exception) {
            t
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    sealed class AuthResult {
        data class Ok(val required: Boolean) : AuthResult()
        data class Denied(val error: String) : AuthResult()
    }
}

/** Monotonic settings revision for apply/import optimistic concurrency. */
object FleetSettingsRevision {
    private val revision = AtomicLong(1L)

    fun current(): Long = revision.get()

    fun bump(): Long = revision.incrementAndGet()
}
