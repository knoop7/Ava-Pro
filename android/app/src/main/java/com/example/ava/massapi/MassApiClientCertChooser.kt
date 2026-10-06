package com.example.ava.massapi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.util.Log
import com.example.ava.ui.AvaSystemChrome
import java.net.URI

/**
 * Launch the system client-cert picker in a way that actually shows on API 21–36.
 *
 * Why this is version-split (not one call for all):
 * - API 21: only the host/port overload exists. A null host is legal in AOSP but
 *   several 5.x OEM KeyChain builds skip the UI entirely; always pass a host.
 * - API 22–28: Uri overload is available. Prefer it. Still never pass keyTypes /
 *   issuers — a non-empty filter with no matching certs **suppresses the prompt**.
 * - API 29–32: device/profile owners intercept via
 *   [android.app.admin.DeviceAdminReceiver.onChoosePrivateKeyAlias]. Returning
 *   null there still shows the chooser; we do not deny.
 * - API 30+: package visibility. Chooser is an explicit system activity, but we
 *   still probe `com.android.keychain` before start (kiosk ROMs often strip it).
 * - API 33–36: callback arrives on a Binder thread; hop to main before touching
 *   settings / Compose. Same hop is used on older APIs for uniformity.
 *
 * Immersive sticky (especially landscape FULLSCREEN + HIDE_NAVIGATION) re-hides
 * bars 500ms after the chooser appears and eats taps on the system sheet. Bars
 * are shown and the listener is cleared for the duration of the pick.
 */
internal object MassApiClientCertChooser {
    private const val TAG = "MassApiCertChooser"

    const val PKCS12_ALIAS = "app-pkcs12"
    const val PKCS12_FILE = "mass_api_client.p12"

    private const val KEYCHAIN_PACKAGE = "com.android.keychain"
    private const val FALLBACK_HOST = "localhost"
    private const val FALLBACK_PORT = 443

    fun isKeyChainChooserAvailable(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    KEYCHAIN_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(KEYCHAIN_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @return true if the system KeyChain UI was started. False means the caller
     * should open a PKCS#12 file picker (missing KeyChain / start failed).
     */
    fun launch(
        activity: Activity,
        preselectAlias: String?,
        serverUrl: String,
        landscape: Boolean,
        onAlias: (String?) -> Unit,
        onChooserFailed: (message: String) -> Unit,
    ): Boolean {
        if (!isKeyChainChooserAvailable(activity)) {
            Log.w(TAG, "com.android.keychain missing (typical kiosk ROM)")
            return false
        }
        val alias = preselectAlias?.takeUnless { it == PKCS12_ALIAS }
        val callback = KeyChainAliasCallback { chosen ->
            activity.runOnUiThread {
                restoreImmersive(activity, landscape)
                onAlias(chosen)
            }
        }
        prepareSystemChooser(activity)
        // Apply bar visibility before startActivity; OEM KeyChain reads the
        // current window flags at launch (API 21–29 especially).
        activity.window.decorView.post {
            try {
                chooseByApi(activity, callback, serverUrl, alias)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "KeyChain chooser activity missing", e)
                restoreImmersive(activity, landscape)
                onChooserFailed(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                Log.e(TAG, "KeyChain.choosePrivateKeyAlias failed", e)
                restoreImmersive(activity, landscape)
                onChooserFailed(e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    private fun chooseByApi(
        activity: Activity,
        callback: KeyChainAliasCallback,
        serverUrl: String,
        alias: String?,
    ) {
        // keyTypes / issuers stay null on every API: a filter that matches
        // nothing suppresses the entire prompt (documented KeyChain behavior).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // API 22–36: Uri overload. Never pass a null Uri — some OEM
            // KeyChain builds NPE or skip the UI (seen on 10–12).
            KeyChain.choosePrivateKeyAlias(
                activity,
                callback,
                null,
                null,
                chooserUri(serverUrl),
                alias,
            )
        } else {
            // API 21 only.
            val (host, port) = chooserHostPort(serverUrl)
            KeyChain.choosePrivateKeyAlias(
                activity,
                callback,
                null,
                null,
                host,
                port,
                alias,
            )
        }
    }

    private fun prepareSystemChooser(activity: Activity) {
        AvaSystemChrome.clearImmersiveModeListener(activity)
        AvaSystemChrome.showSystemBarsForDialog(activity)
    }

    private fun restoreImmersive(activity: Activity, landscape: Boolean) {
        if (activity.isFinishing) return
        AvaSystemChrome.applyImmersiveMode(activity, landscape)
        AvaSystemChrome.installImmersiveModeListener(activity, landscape)
    }

    /** API 22+ chooser context. Scheme + host so getHost() is never null. */
    internal fun chooserUri(serverUrl: String): Uri {
        val parsed = parseServer(serverUrl)
        val scheme = parsed.scheme?.takeIf { it.equals("https", true) || it.equals("http", true) }
            ?: "https"
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: FALLBACK_HOST
        val port = parsed.port.takeIf { it > 0 } ?: if (scheme == "http") 80 else FALLBACK_PORT
        return Uri.Builder()
            .scheme(scheme)
            .encodedAuthority("$host:$port")
            .build()
    }

    /** API 21 host/port. Null host is avoided on purpose. */
    internal fun chooserHostPort(serverUrl: String): Pair<String, Int> {
        val parsed = parseServer(serverUrl)
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: FALLBACK_HOST
        val port = parsed.port.takeIf { it > 0 }
            ?: if (parsed.scheme.equals("http", true)) 80 else FALLBACK_PORT
        return host to port
    }

    private fun parseServer(serverUrl: String): URI {
        val raw = serverUrl.trim()
        if (raw.isBlank()) return URI("https://$FALLBACK_HOST")
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        return runCatching { URI(withScheme) }.getOrElse { URI("https://$FALLBACK_HOST") }
    }
}
