package com.example.ava.utils

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

object SoundUriPersistence {
    fun persist(context: Context, uriString: String, destBaseName: String): String {
        if (uriString.isBlank() || uriString.startsWith("asset://")) return uriString

        val soundsDir = File(context.filesDir, "sounds").apply { mkdirs() }
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val path = uri.path ?: return uriString
            if (path.startsWith(soundsDir.absolutePath)) return uriString
        }

        val ext = guessExtension(context, uri)
        val outFile = File(soundsDir, "$destBaseName$ext")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return uriString
            Uri.fromFile(outFile).toString()
        } catch (_: Exception) {
            uriString
        }
    }

    private fun guessExtension(context: Context, uri: Uri): String {
        context.contentResolver.getType(uri)?.let { mime ->
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.isNotBlank() }?.let {
                return ".$it"
            }
        }
        uri.path?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.let {
            return ".$it"
        }
        return ".audio"
    }
}
