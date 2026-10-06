package com.example.ava.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import com.example.ava.MainActivity
import java.lang.ref.WeakReference

object WebViewPermissionCoordinator {
    private const val TAG = "WebViewPermissionCoord"
    const val ACTION_REQUEST_WEBVIEW_AUDIO_PERMISSION =
        "com.example.ava.action.REQUEST_WEBVIEW_AUDIO_PERMISSION"

    @Volatile
    private var pendingRequest: WeakReference<PermissionRequest>? = null

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun handlePermissionRequest(context: Context, request: PermissionRequest): Boolean {
        val requestedResources = request.resources
        if (!requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            return false
        }

        if (hasRecordAudioPermission(context)) {
            grantAudioRequest(request)
            return true
        }

        pendingRequest = WeakReference(request)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_REQUEST_WEBVIEW_AUDIO_PERMISSION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
        return true
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        val request = pendingRequest?.get()
        pendingRequest = null

        if (request == null) {
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                if (granted) {
                    grantAudioRequest(request)
                } else {
                    request.deny()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize WebView permission request", e)
            }
        }
    }

    fun denyPendingRequest() {
        onRecordAudioPermissionResult(false)
    }

    private fun grantAudioRequest(request: PermissionRequest) {
        val audioResources = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (audioResources.isEmpty()) {
            request.deny()
            return
        }
        request.grant(audioResources.toTypedArray())
    }
}
