package com.example.ava.localllm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed tool surface for the Needle middle layer. Device slots are spoken
 * strings; [DeviceIndex] maps them onto entity ids after the call.
 */
sealed class ToolParamType {
    data object Str : ToolParamType()
    data object Obj : ToolParamType()
    data object Bool : ToolParamType()
    data class Enum(val values: List<String>) : ToolParamType()
    data class Int(val min: kotlin.Int, val max: kotlin.Int) : ToolParamType()
    data class Num(val min: Double, val max: Double) : ToolParamType()
}

data class ToolParam(
    val name: String,
    val type: ToolParamType,
    val description: String,
    val required: Boolean,
)

data class ToolDef(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val argumentCases: List<ToolArgumentCase> = emptyList(),
)

/** The same branch rules drive JSON Schema and host validation. */
data class ToolArgumentCase(
    val action: String? = null,
    val fields: Set<String>,
    val required: Set<String> = emptySet(),
    val exactlyOne: Set<String> = emptySet(),
    val atLeastOne: Set<String> = emptySet(),
)

class HaToolSet(
    val tools: List<ToolDef>,
    /** Optional short names shown in the Needle system prompt only. */
    val hintNames: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = tools.isEmpty()

    /** Needle / OpenAI-style function schema array. */
    fun toJsonSchema(): String {
        val arr = JSONArray()
        for (tool in tools) {
            val props = JSONObject()
            val required = JSONArray()
            for (p in tool.params) {
                val schema = JSONObject()
                when (val t = p.type) {
                    ToolParamType.Str -> schema.put("type", "string")
                    ToolParamType.Obj -> schema.put("type", "object")
                    ToolParamType.Bool -> schema.put("type", "boolean")
                    is ToolParamType.Enum -> {
                        schema.put("type", "string")
                        schema.put("enum", JSONArray(t.values))
                    }
                    is ToolParamType.Int -> {
                        schema.put("type", "integer")
                        schema.put("minimum", t.min)
                        schema.put("maximum", t.max)
                    }
                    is ToolParamType.Num -> {
                        schema.put("type", "number")
                        schema.put("minimum", t.min)
                        schema.put("maximum", t.max)
                    }
                }
                if (p.description.isNotBlank()) schema.put("description", p.description)
                props.put(p.name, schema)
                if (p.required) required.put(p.name)
            }
            val parameters = JSONObject()
                .put("type", "object").put("properties", props)
                .put("required", required).put("additionalProperties", false)
            if (tool.argumentCases.isNotEmpty()) {
                val cases = JSONArray()
                val actions = (tool.params.firstOrNull { it.name == "action" }?.type as? ToolParamType.Enum)?.values
                for (case in tool.argumentCases.filter { it.action == null || actions == null || it.action in actions }) {
                    val fields = case.fields + if (case.action != null) setOf("action") else emptySet()
                    val branchProps = JSONObject()
                    for (field in fields) branchProps.put(field, props.getJSONObject(field))
                    if (case.action != null) branchProps.put("action", JSONObject().put("enum", JSONArray(listOf(case.action))))
                    val branch = JSONObject().put("type", "object").put("properties", branchProps)
                        .put("additionalProperties", false)
                        .put("required", JSONArray((case.required + if (case.action != null) setOf("action") else emptySet()).toList()))
                    if (case.exactlyOne.isNotEmpty()) branch.put("oneOf", JSONArray(case.exactlyOne.map {
                        JSONObject().put("required", JSONArray(listOf(it)))
                    }))
                    if (case.atLeastOne.isNotEmpty()) branch.put("anyOf", JSONArray(case.atLeastOne.map {
                        JSONObject().put("required", JSONArray(listOf(it)))
                    }))
                    cases.put(branch)
                }
                parameters.put("oneOf", cases)
            }
            arr.put(JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", parameters))
        }
        return arr.toString()
    }

    /**
     * GBNF grammar (llama.cpp) admitting only `[]` or a JSON array of calls whose
     * names / enum values come from [tools]. Required params first in declared
     * order, optional ones after, each individually omittable.
     */
    fun toGbnf(): String {
        val sb = StringBuilder()
        sb.append("root ::= \"[\" ws (call (ws \",\" ws call)*)? ws \"]\"\n")
        sb.append("call ::= ").append(tools.indices.joinToString(" | ") { "call$it" }).append('\n')
        sb.append("ws ::= [ \\t\\n]*\n")
        sb.append("json-object ::= \"{\" ws (string ws \":\" ws json-value (ws \",\" ws string ws \":\" ws json-value)*)? ws \"}\"\n")
        sb.append("json-array ::= \"[\" ws (json-value (ws \",\" ws json-value)*)? ws \"]\"\n")
        sb.append("json-value ::= string | number | boolean | \"null\" | json-object | json-array\n")
        sb.append("boolean ::= \"true\" | \"false\"\n")
        sb.append("integer ::= \"-\"? [0-9]+\n")
        sb.append("number ::= \"-\"? [0-9]+ (\".\" [0-9]+)?\n")
        sb.append("string ::= \"\\\"\" [^\"\\\\\\x7F\\x00-\\x1F]* \"\\\"\"\n")
        tools.forEachIndexed { ti, tool ->
            sb.append("call").append(ti).append(" ::= \"{\" ws \"\\\"name\\\"\" ws \":\" ws \"")
                .append(gbnfLiteralBody(tool.name)).append("\" ws \",\" ws \"\\\"arguments\\\"\" ws \":\" ws args")
                .append(ti).append(" ws \"}\"\n")
            val required = tool.params.filter { it.required }
            val optional = tool.params.filter { !it.required }
            sb.append("args").append(ti).append(" ::= \"{\" ws ")
            required.forEachIndexed { pi, p ->
                if (pi > 0) sb.append("\",\" ws ")
                sb.append(paramRule(ti, p)).append(" ws ")
            }
            optional.forEach { p ->
                sb.append('(')
                if (required.isNotEmpty()) sb.append("\",\" ws ")
                sb.append(paramRule(ti, p)).append(" ws)? ")
            }
            sb.append("\"}\"\n")
            tool.params.forEach { p ->
                val t = p.type
                if (t is ToolParamType.Enum) {
                    sb.append("enum_").append(ti).append('_').append(p.name).append(" ::= ")
                        .append(t.values.joinToString(" | ") { "\"\\\"${gbnfLiteralBody(it)}\\\"\"" })
                        .append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun paramRule(ti: Int, p: ToolParam): String {
        val value = when (p.type) {
            ToolParamType.Str -> "string"
            ToolParamType.Obj -> "json-object"
            ToolParamType.Bool -> "boolean"
            is ToolParamType.Enum -> "enum_${ti}_${p.name}"
            is ToolParamType.Int -> "integer"
            is ToolParamType.Num -> "number"
        }
        return "\"\\\"${p.name}\\\"\" ws \":\" ws $value"
    }

    private fun gbnfLiteralBody(raw: String): String = buildString {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n', '\r', '\t' -> append(' ')
                else -> append(ch)
            }
        }
    }

    companion object {
        const val TOOL_POWER = "set_power"
        const val TOOL_COVER = "cover"
        const val TOOL_VACUUM = "vacuum"
        const val TOOL_CLIMATE = "climate"
        const val TOOL_ACTIVATE = "activate"
        const val TOOL_MEDIA = "media"
        const val TOOL_PLAY = "play_media"
        const val TOOL_BATCH = "set_all"
        const val TOOL_QUERY = "get_state"
        const val TOOL_REPLY = "reply"
        const val SERVICE_SEARCH_AND_PLAY = "media.search_and_play"

        fun empty(): HaToolSet = HaToolSet(emptyList())

        fun replyOnly(): HaToolSet = HaToolSet(
            tools = listOf(
                ToolDef(
                    TOOL_REPLY,
                    "Speak a short reply to the user.",
                    listOf(ToolParam("text", ToolParamType.Str, "spoken reply", required = true)),
                ),
            ),
        )

        private val DEVICE = ToolParam(
            "device",
            ToolParamType.Str,
            "spoken device name from the user; the host fuzzy-matches it",
            required = true,
        )
        private val DOMAIN = ToolParam(
            "domain",
            ToolParamType.Str,
            "optional Home Assistant domain such as light or switch",
            required = false,
        )

        /** Stable Needle tools. Names are matched later by [DeviceIndex]. */
        fun needleSurface(hintNames: List<String> = emptyList()): HaToolSet = HaToolSet(
            tools = listOf(
                ToolDef(
                    TOOL_POWER,
                    "Turn a light, switch, fan or player on or off. For lights, optionally set brightness or color.",
                    listOf(
                        DEVICE,
                        DOMAIN,
                        ToolParam("on", ToolParamType.Bool, "true to turn on, false to turn off", required = true),
                        ToolParam("brightness_pct", ToolParamType.Int(1, 100), "light brightness percent", required = false),
                        ToolParam("color_name", ToolParamType.Str, "spoken color; host maps it to HA", required = false),
                    ),
                ),
                ToolDef(
                    TOOL_COVER,
                    "Open, close or stop curtains, blinds, shutters, garage doors; optionally set position percent.",
                    listOf(
                        DEVICE,
                        DOMAIN,
                        ToolParam("action", ToolParamType.Enum(listOf("open", "close", "stop")), "", required = true),
                        ToolParam("position", ToolParamType.Int(0, 100), "0 closed, 100 fully open", required = false),
                    ),
                ),
                ToolDef(
                    TOOL_VACUUM,
                    "Control a robot vacuum: start, stop, pause, return to dock, or locate.",
                    listOf(
                        DEVICE,
                        DOMAIN,
                        ToolParam(
                            "action",
                            ToolParamType.Enum(listOf("start", "stop", "pause", "return_to_base", "locate")),
                            "",
                            required = true,
                        ),
                    ),
                ),
                ToolDef(
                    TOOL_CLIMATE,
                    "Set a thermostat or air conditioner target temperature and/or mode.",
                    listOf(
                        DEVICE,
                        DOMAIN,
                        ToolParam("temperature", ToolParamType.Num(5.0, 35.0), "target temperature in degrees", required = false),
                        ToolParam(
                            "hvac_mode",
                            ToolParamType.Enum(listOf("off", "heat", "cool", "auto", "heat_cool", "dry", "fan_only")),
                            "",
                            required = false,
                        ),
                    ),
                ),
                ToolDef(
                    TOOL_ACTIVATE,
                    "Activate a scene, run a script or trigger an automation by name.",
                    listOf(ToolParam("name", ToolParamType.Str, "spoken scene or script name", required = true)),
                ),
                ToolDef(
                    TOOL_PLAY,
                    "Search and play a song, artist, album, playlist or radio by spoken name. Host resolves the title.",
                    listOf(
                        ToolParam("query", ToolParamType.Str, "spoken song, artist, album or playlist name", required = true),
                        ToolParam(
                            "device",
                            ToolParamType.Str,
                            "optional spoken player name; host fuzzy-matches it",
                            required = false,
                        ),
                        DOMAIN,
                        ToolParam("artist", ToolParamType.Str, "optional artist name", required = false),
                        ToolParam(
                            "media_class",
                            ToolParamType.Enum(listOf("music", "playlist", "album", "artist", "radio")),
                            "",
                            required = false,
                        ),
                    ),
                ),
                ToolDef(
                    TOOL_BATCH,
                    "Turn every matching device on or off. Use for all lights in an area.",
                    listOf(
                        ToolParam("domain", ToolParamType.Str, "Home Assistant domain such as light or switch", required = true),
                        ToolParam("on", ToolParamType.Bool, "true to turn on, false to turn off", required = true),
                        ToolParam("area", ToolParamType.Str, "optional spoken area name", required = false),
                    ),
                ),
                ToolDef(
                    TOOL_MEDIA,
                    "Control media playback or volume on a speaker or TV.",
                    listOf(
                        DEVICE,
                        DOMAIN,
                        ToolParam(
                            "action",
                            ToolParamType.Enum(
                                listOf("play", "pause", "stop", "next", "previous", "volume_up", "volume_down", "mute", "unmute", "set_volume"),
                            ),
                            "",
                            required = true,
                        ),
                        ToolParam("volume_pct", ToolParamType.Int(0, 100), "volume percent for set_volume", required = false),
                    ),
                ),
                ToolDef(
                    TOOL_QUERY,
                    "Read the current state or value of a sensor or device.",
                    listOf(DEVICE, DOMAIN),
                ),
            ),
            hintNames = hintNames.filter { it.isNotBlank() }.distinct().take(DeviceIndex.HINT_LIMIT),
        )
    }
}
