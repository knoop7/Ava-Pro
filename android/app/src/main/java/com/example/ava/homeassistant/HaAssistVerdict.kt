package com.example.ava.homeassistant

import org.json.JSONArray

/**
 * Structured outcome of one Assist pipeline run, read from the pipeline debug log.
 *
 * The ESPHome voice-assistant channel only forwards the spoken `speech` string, so the
 * satellite alone cannot tell "Sorry, I couldn't understand" from a real answer. The
 * `intent-end` debug event still carries `conversation.ConversationResult.as_dict()`:
 * `response.response_type` and, for errors, `response.data.code` — one of
 * `homeassistant.helpers.intent.IntentResponseErrorCode`.
 */
data class HaAssistVerdict(
    /** `intent-start.data.intent_input` — the text the agent actually processed. */
    val intentInput: String,
    /** `action_done` / `query_answer` / `error`. */
    val responseType: String,
    /** `no_intent_match` / `no_valid_targets` / `failed_to_handle` / `unknown`, or null. */
    val errorCode: String?,
    /** True when the built-in Home Assistant agent (hassil) handled it, not an LLM. */
    val processedLocally: Boolean,
    val speech: String,
) {
    /**
     * Assist recognised nothing usable. `failed_to_handle` / `unknown` are excluded on
     * purpose: the intent *was* understood, the target just failed, so redoing it locally
     * would repeat the failure.
     */
    val isMiss: Boolean
        get() = responseType == "error" && (errorCode == ERR_NO_INTENT || errorCode == ERR_NO_TARGETS)

    companion object {
        const val ERR_NO_INTENT = "no_intent_match"
        const val ERR_NO_TARGETS = "no_valid_targets"

        /** Parse `assist_pipeline/pipeline_debug/get` events. Null when the run had no intent stage. */
        fun fromEvents(events: JSONArray): HaAssistVerdict? {
            var input: String? = null
            var verdict: HaAssistVerdict? = null
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val data = event.optJSONObject("data")
                when (event.optString("type")) {
                    "intent-start" -> input = data?.optString("intent_input")
                    "intent-end" -> {
                        val output = data?.optJSONObject("intent_output") ?: continue
                        val response = output.optJSONObject("response") ?: continue
                        verdict = HaAssistVerdict(
                            intentInput = input.orEmpty(),
                            responseType = response.optString("response_type"),
                            errorCode = response.optJSONObject("data")?.optString("code")?.takeIf { it.isNotBlank() },
                            processedLocally = data.optBoolean("processed_locally", false),
                            speech = response.optJSONObject("speech")
                                ?.optJSONObject("plain")
                                ?.optString("speech")
                                .orEmpty(),
                        )
                    }
                }
            }
            return verdict
        }
    }
}

/** Registry-derived spoken names for one entity: name override, aliases, and its area. */
data class HaEntityNames(
    val names: List<String>,
    val areaNames: List<String>,
)
