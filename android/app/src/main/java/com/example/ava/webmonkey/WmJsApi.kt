package com.example.ava.webmonkey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.ava.ui.AvaToast

interface IBrowser {
    fun getCurrentUrl(): String?
    fun setCurrentUrl(url: String)
}

class WmJsApi(
    val secret: String,
    private val context: Context,
    private val webview: WebView,
    private val browser: IBrowser?
) {
    companion object {
        const val TAG = "WebViewGmApi"
        const val GlobalJsApiNamespace = "WebViewWM"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getJsInterface(): Any {
        return object {
            @JavascriptInterface
            fun toast(scriptName: String, scriptNamespace: String, secret: String, duration: Int, message: String) {
                if (this@WmJsApi.secret != secret) {
                    Log.e(TAG, "Call to toast did not supply correct secret")
                    return
                }
                mainHandler.post {
                    try {
                        val ms = if (duration > 0) AvaToast.LONG_MS else AvaToast.SHORT_MS
                        AvaToast.show(context, message, tag = "wm-js", durationMs = ms)
                    } catch (e: Exception) {
                        Log.e(TAG, "toast error", e)
                    }
                }
            }

            @JavascriptInterface
            fun getUrl(scriptName: String, scriptNamespace: String, secret: String): String? {
                if (this@WmJsApi.secret != secret) {
                    Log.e(TAG, "Call to getUrl did not supply correct secret")
                    return null
                }
                return browser?.getCurrentUrl() ?: webview.url
            }

            @JavascriptInterface
            fun log(scriptName: String, scriptNamespace: String, secret: String, message: String) {
                if (this@WmJsApi.secret != secret) return
                Log.d(TAG, "[$scriptName] $message")
            }

            @JavascriptInterface
            fun startIntent(scriptName: String, scriptNamespace: String, secret: String, action: String?, data: String?, type: String?, extras: Array<String>?) {
                if (this@WmJsApi.secret != secret) {
                    Log.e(TAG, "Call to startIntent did not supply correct secret")
                    return
                }
                try {
                    val intent = Intent()
                    if (!action.isNullOrEmpty()) intent.action = action
                    if (!data.isNullOrEmpty()) {
                        if (!type.isNullOrEmpty()) {
                            intent.setDataAndType(Uri.parse(data), type)
                        } else {
                            intent.data = Uri.parse(data)
                        }
                    } else if (!type.isNullOrEmpty()) {
                        intent.type = type
                    }
                    
                    if (extras != null && extras.size >= 2) {
                        val length = if (extras.size % 2 == 0) extras.size else extras.size - 1
                        for (i in 0 until length step 2) {
                            intent.putExtra(extras[i], extras[i + 1])
                        }
                    }
                    
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "startIntent error", e)
                }
            }

            @JavascriptInterface
            fun loadUrl(scriptName: String, scriptNamespace: String, secret: String, url: String, headers: Array<String>?) {
                if (this@WmJsApi.secret != secret) {
                    Log.e(TAG, "Call to loadUrl did not supply correct secret")
                    return
                }
                mainHandler.post {
                    try {
                        webview.stopLoading()
                        if (headers != null && headers.size >= 2) {
                            val httpHeaders = mutableMapOf<String, String>()
                            val length = if (headers.size % 2 == 0) headers.size else headers.size - 1
                            for (i in 0 until length step 2) {
                                httpHeaders[headers[i]] = headers[i + 1]
                            }
                            webview.loadUrl(url, httpHeaders)
                        } else {
                            webview.loadUrl(url)
                        }
                        browser?.setCurrentUrl(url)
                    } catch (e: Exception) {
                        Log.e(TAG, "loadUrl error", e)
                    }
                }
            }

            @JavascriptInterface
            fun getUserAgent(scriptName: String, scriptNamespace: String, secret: String): String? {
                if (this@WmJsApi.secret != secret) {
                    Log.e(TAG, "Call to getUserAgent did not supply correct secret")
                    return null
                }
                return webview.settings.userAgentString
            }
        }
    }
}
