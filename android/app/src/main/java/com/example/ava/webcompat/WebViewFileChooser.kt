package com.example.ava.webcompat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * `<input type=file>` support for the overlay browser (both engines).
 *
 * File pickers need an Activity, but the browser lives in a service overlay. This trampolines
 * through a transparent activity and hands the picked URIs back to the pending callback —
 * WebView's [WebChromeClient.onShowFileChooser] and Gecko's `PromptDelegate.onFilePrompt`
 * both route through here.
 */
object WebViewFileChooserCoordinator {
    private const val TAG = "WebViewFileChooser"

    @Volatile
    private var pendingResult: ((Array<Uri>?) -> Unit)? = null
    @Volatile
    private var pendingIntent: Intent? = null

    /** WebView entry point. */
    fun launch(
        context: Context,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        val picker = try {
            params?.createIntent()
        } catch (e: Exception) {
            Log.w(TAG, "createIntent failed, falling back to OPEN_DOCUMENT", e)
            null
        } ?: defaultPickerIntent()
        return launchWithIntent(context, picker) { uris -> callback.onReceiveValue(uris) }
    }

    /** Engine-agnostic entry point. [onResult] receives null when cancelled. */
    fun launchWithIntent(
        context: Context,
        pickerIntent: Intent,
        onResult: (Array<Uri>?) -> Unit,
    ): Boolean {
        pendingResult?.invoke(null)
        pendingResult = onResult
        pendingIntent = preparePickerIntent(pickerIntent)

        return try {
            context.startActivity(
                Intent(context, WebViewFileChooserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch file chooser activity", e)
            pendingResult = null
            pendingIntent = null
            false
        }
    }

    private fun defaultPickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }

    private fun preparePickerIntent(intent: Intent): Intent {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        return intent
    }

    internal fun consumePickerIntent(): Intent? {
        val intent = pendingIntent
        pendingIntent = null
        return intent
    }

    internal fun deliverResult(context: Context, resultCode: Int, data: Intent?) {
        val callback = pendingResult
        pendingResult = null
        if (callback == null) return

        val uris: Array<Uri>? = when {
            resultCode != Activity.RESULT_OK -> null
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            else -> null
        }
        try {
            uris?.let { grantReadAccess(context, it) }
            callback(uris)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver file chooser result", e)
            callback(null)
        }
    }

    /** WebView / Gecko need explicit read grants on content:// URIs returned from the picker. */
    private fun grantReadAccess(context: Context, uris: Array<Uri>) {
        val appContext = context.applicationContext
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        for (uri in uris) {
            try {
                appContext.grantUriPermission(appContext.packageName, uri, readFlag)
            } catch (e: Exception) {
                Log.w(TAG, "grantUriPermission failed for $uri", e)
            }
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, readFlag)
            } catch (e: Exception) {
                // GET_CONTENT pickers may not support persistable grants — that's fine.
                Log.d(TAG, "takePersistableUriPermission skipped for $uri")
            }
        }
    }

    fun cancel() {
        pendingResult?.invoke(null)
        pendingResult = null
        pendingIntent = null
    }
}

/** Invisible trampoline that runs the system file picker on behalf of the overlay browser. */
class WebViewFileChooserActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picker = WebViewFileChooserCoordinator.consumePickerIntent()
        if (picker == null) {
            finish()
            return
        }
        try {
            startActivityForResult(picker, REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("WebViewFileChooser", "No activity to handle file picker", e)
            WebViewFileChooserCoordinator.cancel()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            WebViewFileChooserCoordinator.deliverResult(this, resultCode, data)
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 47001
    }
}
