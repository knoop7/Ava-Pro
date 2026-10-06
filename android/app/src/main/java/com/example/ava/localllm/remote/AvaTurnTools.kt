package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * The continue/end call continuous conversation already honors.
 *
 * Present only while smart continue is on. A spoken reply that skips this
 * call is not accepted; the turn loop asks again. The satellite reads
 * [keepListening] when the local reply starts playing.
 */
object AvaTurnTools {

    const val NAME = "ava_turn"

    const val NUDGE =
        "Call ava_turn before the spoken reply. continue=true keeps listening for another sentence. continue=false ends this exchange. Then reply with speech only, and no tool call."

    private val decision = AtomicReference<Boolean?>(null)

    fun begin() {
        decision.set(null)
    }

    fun called(): Boolean = decision.get() != null

    /** True only after this turn's call set continue=true. */
    fun keepListening(): Boolean = decision.get() == true

    fun surface(): HaToolSet = HaToolSet(
        listOf(
            ToolDef(
                NAME,
                "Required once before the spoken reply. continue=true keeps the microphone open for another sentence after this reply. continue=false ends the exchange. Do not skip this call.",
                listOf(
                    ToolParam(
                        "continue",
                        ToolParamType.Bool,
                        "true: keep listening after the spoken reply. false: this exchange is finished.",
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
