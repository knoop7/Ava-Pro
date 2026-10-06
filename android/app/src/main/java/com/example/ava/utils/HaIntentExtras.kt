package com.example.ava.utils

import android.content.Intent
import java.net.URLDecoder

/**
 * Home Assistant Companion App compatible `intent_extras` parser.
 *
 * Port of `MessagingManager.addExtrasToIntent` from
 * [home-assistant/android](https://github.com/home-assistant/android):
 * format `name:value[,name:value:type…]` with array values split by `;`.
 */
object HaIntentExtras {
    fun addExtrasToIntent(intent: Intent, extras: String) {
        if (extras.isBlank()) return
        val items = extras.split(',')
        for (item in items) {
            if (item.isBlank()) continue
            val chunks = item.split(":")
            if (chunks.size < 2) continue
            val name = chunks[0]
            var value = chunks[1]
            val hasTypeInfo = chunks.size > 2

            if (hasTypeInfo) {
                value = chunks.subList(1, chunks.lastIndex).joinToString(":")
                when (val type = chunks.last()) {
                    "int" -> intent.putExtra(name, value.toInt())
                    "int[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toInt() }.toIntArray(),
                    )
                    "ArrayList<Integer>" -> intent.putIntegerArrayListExtra(
                        name,
                        value.split(";").map { it.toInt() }.toCollection(ArrayList()),
                    )
                    "double" -> intent.putExtra(name, value.toDouble())
                    "double[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toDouble() }.toDoubleArray(),
                    )
                    "float" -> intent.putExtra(name, value.toFloat())
                    "float[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toFloat() }.toFloatArray(),
                    )
                    "long" -> intent.putExtra(name, value.toLong())
                    "long[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toLong() }.toLongArray(),
                    )
                    "short" -> intent.putExtra(name, value.toShort())
                    "short[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toShort() }.toShortArray(),
                    )
                    "byte" -> intent.putExtra(name, value.toByte())
                    "byte[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toByte() }.toByteArray(),
                    )
                    "boolean" -> intent.putExtra(name, value.toBoolean())
                    "boolean[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it.toBoolean() }.toBooleanArray(),
                    )
                    "char" -> intent.putExtra(name, value[0])
                    "char[]" -> intent.putExtra(
                        name,
                        value.split(";").map { it[0] }.toCharArray(),
                    )
                    "String" -> intent.putExtra(name, value)
                    "String.urlencoded", "urlencoded" -> intent.putExtra(
                        name,
                        URLDecoder.decode(value, Charsets.UTF_8.name()),
                    )
                    "String[]" -> intent.putExtra(
                        name,
                        value.split(";").toTypedArray(),
                    )
                    "ArrayList<String>" -> intent.putStringArrayListExtra(
                        name,
                        value.split(";").toCollection(ArrayList()),
                    )
                    "String[].urlencoded" -> intent.putExtra(
                        name,
                        value.split(";").map { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                            .toTypedArray(),
                    )
                    // Source uses ArrayList<String>.urlencoded; docs also list ArrayList.urlencoded.
                    "ArrayList<String>.urlencoded", "ArrayList.urlencoded" ->
                        intent.putStringArrayListExtra(
                            name,
                            value.split(";").map { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                                .toCollection(ArrayList()),
                        )
                    else -> intent.putExtra(name, value) // unknown type → string (HA else)
                }
            } else {
                when {
                    value.isDigitsOnlyCompat() -> intent.putExtra(name, value.toInt())
                    value.equals("true", ignoreCase = true) ||
                        value.equals("false", ignoreCase = true) ->
                        intent.putExtra(name, value.toBoolean())
                    else -> intent.putExtra(name, value)
                }
            }
        }
    }

    /** Same semantics as Android KTX `CharSequence.isDigitsOnly()` used by HA Companion. */
    private fun String.isDigitsOnlyCompat(): Boolean =
        isNotEmpty() && all { it in '0'..'9' }
}
