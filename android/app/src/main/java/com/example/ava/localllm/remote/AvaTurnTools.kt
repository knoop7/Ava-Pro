package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * The end call for smart continue.
 *
 * Present only while that mode is on. The microphone stays open after the
 * spoken reply unless this turn set continue=false. A skipped call does not
 * end the exchange: continuous conversation is already the mode.
 */
object AvaTurnTools {

    const val NAME = "ava_turn"

    const val NUDGE =
        "Call ava_turn before the spoken reply. continue=true waits for the user's next sentence. continue=false only if they are done or saying goodbye (好了, 没事了, 谢谢, 再见, that's all), then say a brief goodbye. An answered question still uses true. Then reply with speech only, and no tool call."

    private val decision = AtomicReference<Boolean?>(null)

    fun begin() {
        decision.set(null)
    }

    fun called(): Boolean = decision.get() != null

    /** True only after this turn's call set continue=true. */
    fun keepListening(): Boolean = decision.get() == true

    /** True only after this turn's call set continue=false. A missing call is not an end. */
    fun explicitlyEnded(): Boolean = decision.get() == false

    fun surface(): HaToolSet = HaToolSet(
        listOf(
            ToolDef(
                NAME,
                "Required once before the spoken reply. This is a continuous conversation: continue=true leaves the microphone open for the user's next sentence. continue=false only when they signal they are done (好了, 没事了, 谢谢, 再见, that's all); then the reply is a brief goodbye. Answering the current question is not a reason to stop; a short relevant follow-up question or next-step offer keeps it natural.",
                listOf(
                    ToolParam(
                        "continue",
                        ToolParamType.Bool,
                        "true: wait for another sentence after this reply (the normal choice). false: the user said they are done or goodbye.",
                        required = true,
                    ),
                ),
                argumentCases = listOf(
                    ToolArgumentCase(fields = setOf("continue"), required = setOf("continue")),
                ),
            ),
        ),
    )

    fun execute(call: AvaToolCallback.Call): AvaToolCallback.Result {
        val raw = call.arguments.opt("continue")
        if (raw !is Boolean) {
            return AvaToolCallback.fail("invalid_request", "continue is required and must be true or false")
        }
        decision.set(raw)
        return AvaToolCallback.ok(
            JSONObject()
                .put("continue", raw)
                .put("next", "Spoken reply only. Do not call ava_turn again."),
        )
    }
}
