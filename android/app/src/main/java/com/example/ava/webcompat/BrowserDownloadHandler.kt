package com.example.ava.webcompat

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.example.ava.ui.AvaToast
import com.example.ava.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * Download support for the overlay browser ([android.webkit.DownloadListener] backend).
 *
 * Lovelace cards trigger downloads via `<a download>` clicks — e.g. the WebRTC camera snapshot
 * button produces a `data:image/jpeg;base64,...` URL. WebView surfaces those through
 * onDownloadStart but does nothing itself, so without this handler such buttons are silent no-ops.
 *
 * - `data:` URLs are decoded and written straight into the system Downloads collection.
 * - `http(s)` URLs go through [DownloadManager] with the page's cookies attached.
 * - `blob:` URLs can't be resolved from native code and are logged and ignored.
 */
object BrowserDownloadHandler {
    private const val TAG = "BrowserDownload"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val appContext = context.applicationContext
        when {
            url.startsWith("data:") -> saveDataUrl(appContext, url, contentDisposition, mimeType)
            url.startsWith("blob:") -> Log.w(TAG, "blob: downloads are not supported")
            url.startsWith("http://") || url.startsWith("https://") ->
                enqueueHttpDownload(appContext, url, userAgent, contentDisposition, mimeType)
            else -> Log.w(TAG, "Unsupported download scheme: ${url.substringBefore(':')}")
        }
    }

    /**
     * Save a response body stream into the system Downloads collection.
     * Used by the Gecko engine ([org.mozilla.geckoview.WebResponse] bodies); the WebView path
     * only ever sees URLs, never streams.
     */
    fun saveStream(context: Context, fileName: String, mimeType: String, body: InputStream) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                body.use { input ->
                    copyToDownloads(appContext, fileName, mimeType, input)
                }
                toast(appContext, appContext.getString(R.string.toast_browser_download_saved, fileName))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save download stream", e)
                toast(appContext, appContext.getString(R.string.toast_browser_download_failed))
            }
        }
    }

    private fun copyToDownloads(context: Context, fileName: String, mimeType: String, input: InputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { input.copyTo(it) }
                ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = uniqueFile(dir, fileName)
            var size = 0L
            FileOutputStream(file).use { out: OutputStream ->
                size = input.copyTo(out)
            }
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).addCompletedDownload(
                file.name, file.name, true, mimeType, file.absolutePath, size, true
            )
        }
    }

    private fun saveDataUrl(
        context: Context,
        dataUrl: String,
        contentDisposition: String?,
        mimeTypeHint: String?,
    ) {
        scope.launch {
            try {
                val comma = dataUrl.indexOf(',')
                if (comma < 0) throw IllegalArgumentException("Malformed data URL")
                val header = dataUrl.substring(5, comma) // strip "data:"
                val mimeType = header.substringBefore(';').ifBlank { mimeTypeHint ?: "application/octet-stream" }
                val payload = dataUrl.substring(comma + 1)
                val bytes = if (header.contains("base64")) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(payload, "UTF-8").toByteArray()
                }
                val fileName = fileNameFromDisposition(contentDisposition, mimeType)
                    ?: URLUtil.guessFileName(dataUrl, contentDisposition, mimeType)
                writeToDownloads(context, fileName, mimeType, bytes)
                toast(context, context.getString(R.string.toast_browser_download_saved, fileName))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save data: URL download", e)
                toast(context, context.getString(R.string.toast_browser_download_failed))
            }
        }
    }

    private fun writeToDownloads(context: Context, fileName: String, mimeType: String, bytes: ByteArray) {
        copyToDownloads(context, fileName, mimeType, bytes.inputStream())
    }

    private fun fileNameFromDisposition(contentDisposition: String?, mimeType: String): String? {
        contentDisposition ?: return null
        val star = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)
        if (!star.isNullOrBlank()) {
            return URLDecoder.decode(star.trim(), "UTF-8")
        }
        val plain = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()
        if (!plain.isNullOrBlank()) return plain
        val ext = when {
            mimeType.startsWith("image/jpeg") -> ".jpg"
            mimeType.startsWith("image/png") -> ".png"
            else -> ""
        }
        return if (ext.isNotEmpty()) "download$ext" else null
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var i = 1
        while (file.exists()) {
            val name = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            file = File(dir, name)
            i++
        }
        return file
    }

    private fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            scope.launch { toast(context, context.getString(R.string.toast_browser_download_started, fileName)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
            scope.launch { toast(context, context.getString(R.string.toast_browser_download_failed)) }
        }
    }

    private suspend fun toast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            AvaToast.show(context, message, tag = "browser-download")
        }
    }
}
