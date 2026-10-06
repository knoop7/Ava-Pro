package com.example.ava.homeassistant

import org.json.JSONArray
import org.json.JSONObject

/** Preserve structured service fields all the way to the HA WebSocket payload. */
internal object HaServiceJson {
    fun put(json: JSONObject, key: String, value: Any?) {
        json.put(key, encode(value))
    }

    private fun encode(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean, is Number -> value
        is Map<*, *> -> JSONObject().also { obj ->
            value.forEach { (key, item) ->
                require(key is String) { "JSON object keys must be strings" }
                put(obj, key, item)
            }
        }
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(encode(it)) } }
        is Array<*> -> JSONArray().also { array -> value.forEach { array.put(encode(it)) } }
        is IntArray -> JSONArray().also { array -> value.forEach { array.put(it) } }
        is DoubleArray -> JSONArray().also { array -> value.forEach { array.put(it) } }
        else -> value.toString()
    }
}
