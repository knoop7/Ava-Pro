package com.example.ava.ui.screens.settings.components

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SystemRingtoneLoader {
    suspend fun loadRingtones(
        context: Context,
        ringtoneType: Int,
        prefixEntries: List<Pair<String, String>>,
        unknownLabel: String
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val list = prefixEntries.toMutableList()
        val manager = RingtoneManager(context)
        manager.setType(ringtoneType)
        manager.cursor.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: unknownLabel
                val uriStr = manager.getRingtoneUri(cursor.position).toString()
                list.add(title to uriStr)
            }
        }
        list
    }

    fun resolveCustomTitle(context: Context, soundUri: String, unknownLabel: String): String {
        if (soundUri.isBlank()) return unknownLabel
        if (soundUri.startsWith("asset://")) {
            return soundUri.substringAfterLast('/').ifBlank { unknownLabel }
        }
        return try {
            val uri = Uri.parse(soundUri)
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)?.takeIf { it.isNotBlank() }
                ?: queryDisplayName(context, uri)
                ?: unknownLabel
        } catch (_: Exception) {
            unknownLabel
        }
    }

    fun appendCustomRingtoneEntries(
        base: List<Pair<String, String>>,
        customUris: List<String>,
        context: Context,
        unknownLabel: String,
    ): List<Pair<String, String>> {
        if (customUris.isEmpty()) return base
        val known = base.map { it.second }.toMutableSet()
        val extras = mutableListOf<Pair<String, String>>()
        for (uri in customUris) {
            if (uri.isBlank() || uri in known) continue
            known.add(uri)
            extras.add(resolveCustomTitle(context, uri, unknownLabel) to uri)
        }
        return base + extras
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) return@use null
                cursor.getString(idx)?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveTitle(
        context: Context,
        soundUri: String,
        defaultUri: String,
        defaultLabel: String,
        unknownLabel: String
    ): String = withContext(Dispatchers.IO) {
        when {
            soundUri.isBlank() || soundUri == defaultUri || soundUri.startsWith("asset://") -> defaultLabel
            else -> {
                try {
                    val uri = Uri.parse(soundUri)
                    RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: unknownLabel
                } catch (_: Exception) {
                    unknownLabel
                }
            }
        }
    }
}
