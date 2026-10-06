package com.example.ava.localllm.remote

import kotlinx.coroutines.CancellationException
import android.os.Build
import com.example.ava.homeassistant.HaManager
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Ava host invoke — Claw / HA ChatLog shape, not a shared success string.
 *
 * One [Call.id] → one JSON: `{ok, result}` or `{ok:false, error, message}`.
 * [Context] is Ava-only callback state (speaker, device, locale, HA ready).
 * Features that are off stay out of [Context] (speaker is null).
 */
object AvaToolCallback {

    data class Call(
        val id: String,
        val name: String,
        val arguments: JSONObject,
    )

    data class Context(
        val speaker: String?,
        val deviceName: String,
        val locale: String,
        val haReady: Boolean,
        val browserOnly: Boolean = false,
    )

    data class Policy(
        val enabled: Boolean = true,
        val allow: Set<String> = emptySet(),
        val deny: Set<String> = emptySet(),
    )

    data class Error(
        val type: String,
        val message: String,
        val details: JSONObject? = null,
    )

    data class Result(
        val ok: Boolean,
        val result: Any? = null,
        val error: Error? = null,
        val status: String = "observed",
        /** Host-only JPEG path for the next provider round. Never copied into the JSON wire. */
        val imagePath: String? = null,
    ) {
        fun toJson(): JSONObject {
            val out = JSONObject().put("ok", ok)
            if (ok) {
                out.put("status", status)
                when (result) {
                    null -> out.put("result", JSONObject.NULL)
                    is JSONObject, is JSONArray -> out.put("result", result)
                    is Number, is Boolean -> out.put("result", result)
                    else -> out.put("result", result.toString())
                }
            } else {
                val err = error
                out.put("error", err?.type ?: "tool_error")
                out.put("message", err?.message ?: "tool execution failed")
                err?.details?.let { out.put("details", it) }
                out.put("recovery", when (err?.type) {
                    "invalid_request" -> "correct_arguments"
                    "ambiguous", "ungrounded" -> "clarify_target"
                    "stale_ref" -> "read_elements_again"
                    "tool_call_blocked", "stage_restricted" -> "respect_boundary"
                    "not_found", "not_exposed" -> "clarify_or_narrow_search"
                    "action_ignored" -> "wait_for_state_change"
                    "execution_history_full" -> "start_new_task"
                    "need_accessibility" -> "ask_enable_accessibility"
                    "need_overlay" -> "ask_adb_grant"
                    else -> "explain_failure"
                })
            }
            return out
        }

        fun toWire(): String = toJson().toString()
    }

    fun context(app: android.content.Context): Context {
        val device = RemoteAiMind.thisAvaName(app).orEmpty().ifBlank { "Ava" }
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.resources.configuration.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            app.resources.configuration.locale ?: Locale.getDefault()
        }
        val ha = HaManager.get()?.settingsStore?.getCached()
        return Context(
            speaker = RemoteAiMind.speakerOrNull(app),
            deviceName = device,
            locale = locale.toLanguageTag(),
            haReady = !ha?.serverUrl.isNullOrBlank() && !ha?.accessToken.isNullOrBlank(),
        )
    }

    fun ok(result: Any? = true, status: String = "observed", imagePath: String? = null): Result =
        Result(ok = true, result = result, status = status, imagePath = imagePath)

    fun unknown(message: String, details: JSONObject = JSONObject()): Result =
        ok(details.put("message", message).put("retry_safe", false), status = "unknown")

    fun fail(type: String, message: String, details: JSONObject? = null): Result =
        Result(ok = false, error = Error(type, message, details))

    suspend fun invoke(
        catalog: HaToolSet,
        call: Call,
        ctx: Context,
        policy: Policy = Policy(),
        run: suspend (Call, Context) -> Result,
    ): Result {
        if (!policy.enabled) {
            return AvaUiHere.attachResult(fail("tool_call_blocked", "tools are off"))
        }
        val name = call.name.trim()
        if (name.isEmpty()) return AvaUiHere.attachResult(fail("invalid_request", "tool name is required"))
        if (name in policy.deny) {
            return AvaUiHere.attachResult(fail("tool_call_blocked", "Tool is blocked: $name"))
        }
        if (policy.allow.isNotEmpty() && name !in policy.allow) {
            return AvaUiHere.attachResult(fail("not_found", "Tool not available: $name"))
        }
        val def = catalog.tools.firstOrNull { it.name == name }
            ?: return AvaUiHere.attachResult(fail("not_found", "Tool not available: $name"))
        validate(def, call.arguments)?.let { return AvaUiHere.attachResult(it) }
        return try {
            AvaUiHere.attachResult(run(call.copy(name = name), ctx))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AvaUiHere.attachResult(
                if (RemoteAiExecutionLedger.isWrite(call.name, call.arguments)) {
                    unknown("Execution ended without a reliable acknowledgement. Inspect state before any retry.")
                } else {
                    fail("tool_error", e.message?.takeIf { it.isNotBlank() } ?: "tool execution failed")
                },
            )
        }
    }

    fun fromRemote(call: RemoteAiClient.Call): Call =
        Call(id = call.id, name = call.name, arguments = call.arguments)

    internal fun validate(def: ToolDef, args: JSONObject): Result? {
        val presentKeys = args.keys().asSequence().toSet()
        val unknown = presentKeys - def.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return fail("invalid_request", "unknown fields: ${unknown.joinToString()}")
        for (param in def.params) {
            val present = args.has(param.name)
            if (present && args.opt(param.name) == JSONObject.NULL) return fail("invalid_request", "omit ${param.name} instead of null")
            if (!present) {
                if (param.required) return fail("invalid_request", "missing ${param.name}")
                continue
            }
            typeError(param, args.opt(param.name))?.let { return it }
        }
        if (def.argumentCases.isNotEmpty()) {
            val errors = def.argumentCases.filter { it.action == null || it.action == args.optString("action") }.map { case ->
                val fields = case.fields + if (case.action != null) setOf("action") else emptySet()
                when {
                    (presentKeys - fields).isNotEmpty() -> "fields not valid for this action: ${(presentKeys - fields).joinToString()}"
                    !presentKeys.containsAll(case.required) -> "missing ${ (case.required - presentKeys).joinToString()}"
                    case.exactlyOne.isNotEmpty() && case.exactlyOne.count { it in presentKeys } != 1 -> "give exactly one of ${case.exactlyOne.joinToString()}"
                    case.atLeastOne.isNotEmpty() && case.atLeastOne.none { it in presentKeys } -> "give at least one of ${case.atLeastOne.joinToString()}"
                    else -> null
                }
            }
            if (errors.isEmpty() || errors.none { it == null }) return fail("invalid_request", errors.filterNotNull().distinct().joinToString("; ").ifEmpty { "unsupported action" })
        }
        return null
    }

    private fun typeError(param: ToolParam, raw: Any?): Result? {
        return when (val t = param.type) {
            ToolParamType.Str -> {
                val text = raw as? String ?: return fail("invalid_request", "${param.name} must be a string")
                if (param.required && text.isBlank()) {
                    fail("invalid_request", "${param.name} is empty")
                } else {
                    null
                }
            }
            ToolParamType.Obj -> if (raw is JSONObject) null else fail("invalid_request", "${param.name} must be a JSON object")
            ToolParamType.Bool -> if (raw is Boolean) {
                null
            } else {
                fail("invalid_request", "${param.name} must be boolean")
            }
            is ToolParamType.Enum -> {
                val value = raw?.toString().orEmpty()
                if (value in t.values) null
                else fail("invalid_request", "${param.name} must be one of ${t.values.joinToString()}")
            }
            is ToolParamType.Int -> {
                val n = asInt(raw)
                if (n == null || n !in t.min..t.max) {
                    fail("invalid_request", "${param.name} must be an integer ${t.min}..${t.max}")
                } else {
                    null
                }
            }
            is ToolParamType.Num -> {
                val n = asDouble(raw)
                if (n == null || n !in t.min..t.max) {
                    fail("invalid_request", "${param.name} must be a number ${t.min}..${t.max}")
                } else {
                    null
                }
            }
        }
    }

    private fun asInt(raw: Any?): Int? = when (raw) {
        is Int -> raw
        is Number -> raw.toDouble().takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
        else -> null
    }

    private fun asDouble(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        else -> null
    }
}
