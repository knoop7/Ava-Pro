package com.example.ava.settings

import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "SettingsCorruptionHandler"


private val json = Json { ignoreUnknownKeys = true }

fun <T> defaultCorruptionHandler(default: T) = ReplaceFileCorruptionHandler { exception ->
    Log.e(TAG, "Settings file corrupt; replacing with defaults (original quarantined)", exception)
    default
}

class SettingsSerializer<T>(val serializer: KSerializer<T>, override val defaultValue: T) :
    Serializer<T> {

    // "HaSettings", "PlayerSettings", ... — names the quarantine snapshot.
    private val storeName: String = serializer.descriptor.serialName.substringAfterLast('.')

    override suspend fun readFrom(input: InputStream): T {
        val bytes = input.readBytes()
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (serialization: SerializationException) {
            quarantineAndFail(bytes, serialization)
        } catch (invalid: IllegalArgumentException) {
            quarantineAndFail(bytes, invalid)
        }
    }

    /**
     * The corruption handler will replace the file with defaults; these bytes
     * are the only copy of the user's configuration, so preserve them first.
     */
    private fun quarantineAndFail(bytes: ByteArray, cause: Exception): Nothing {
        SettingsQuarantine.save(storeName, bytes, cause)
        throw CorruptionException("Unable to read Settings ($storeName)", cause)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(
            json.encodeToString(serializer, t)
                .encodeToByteArray()
        )
    }
}
