package com.example.ava.mods

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ModStateStore(context: Context) {
    companion object {
        private const val TAG = "ModStateStore"
        private const val STATE_DIR = "mod_states"
    }

    private val gson = Gson()
    private val stateDir = File(context.filesDir, STATE_DIR).apply { mkdirs() }

    fun getSwitchState(modId: String, objectId: String): Boolean? {
        return readState(modId)[objectId]
    }

    fun saveSwitchState(modId: String, objectId: String, value: Boolean) {
        val current = readState(modId).toMutableMap()
        current[objectId] = value

        val file = stateFile(modId)
        val tempFile = File(stateDir, "$modId.tmp")
        tempFile.writeText(gson.toJson(current))
        if (!tempFile.renameTo(file)) {
            throw IllegalStateException("Failed to save state for mod: $modId")
        }
    }

    private fun readState(modId: String): Map<String, Boolean> {
        val file = stateFile(modId)
        if (!file.exists()) return emptyMap()

        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            gson.fromJson<Map<String, Boolean>>(file.readText(), type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read state for $modId", e)
            emptyMap()
        }
    }

    private fun stateFile(modId: String): File = File(stateDir, "$modId.json")
}
