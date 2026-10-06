package com.example.ava.webcompat

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.ava.settings.BrowserPowerMode
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Host-side identity and scheduling snapshot for the in-HA Health Steward console.
 *
 * Pushes [window.__avaHost] from WebView's own identity APIs
 * ([WebViewRuntime] / [WebView.getCurrentWebViewPackage], multiprocess,
 * default user-agent) so the in-HA console can print chrome://version once.
 */
object BrowserWsStewardHost {

    data class Info(
        val gecko: Boolean,
        val renderHardware: Boolean,
        val powerMode: BrowserPowerMode,
        val pressure: String,
        val dormancy: String,
        val dormantKind: String,
        val liteOn: Boolean,
        val liteMs: Int,
        val split: Boolean,
        val touchBoost: Boolean,
        val stream: Boolean,
        val chunkWanted: Boolean,
        val quiet: Boolean,
        val trim: Boolean,
        val frameThrottle: Boolean,
        val memoryFeatures: Boolean,
        val view: View?,
    )

    private data class CpuTicks(val idle: Long, val total: Long)
    private data class SelfTicks(val ticks: Long, val atMs: Long)

    @Volatile private var lastSys: CpuTicks? = null
    @Volatile private var lastSelf: SelfTicks? = null

    fun snapshotJs(context: Context, info: Info): String {
        val json = try {
            buildJson(context.applicationContext, info)
        } catch (_: Exception) {
            return "(function(){return 'host-err';})();"
        }
        return "(function(){try{window.__avaHost=$json;" +
            "var c=window.__avaStewardConsole;" +
            "if(c&&typeof c.onHost==='function')c.onHost(window.__avaHost);" +
            "}catch(e){}return 'host';})();"
    }

    internal fun buildJson(context: Context, info: Info): JSONObject {
        val runtime = WebViewRuntime.cachedInfo(context)
        val geckoVer = if (info.gecko) geckoVersionName(context) else null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return JSONObject()
            .put("engine", if (info.gecko) "gecko" else "chromium")
            .put("pkg", runtime.packageName)
            .put("ver", runtime.versionName)
            .put("major", runtime.majorVersion)
            .put("multi", webViewMultiProcess())
            .put("ua", defaultUserAgent(context) ?: JSONObject.NULL)
            .put("gecko", geckoVer)
            .put("render", if (info.renderHardware) "hardware" else "software")
            .put("power", info.powerMode.stored)
            .put("hz", displayRefreshHz(context, info.view))
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("cores", cores)
            .put("appCpu", nullable(readAppCpuPercent(cores)))
            .put("sysCpu", nullable(readSysCpuPercent()))
            .put("pressure", info.pressure)
            .put("dormancy", info.dormancy)
            .put("kind", info.dormantKind)
            .put("lite", info.liteOn)
            .put("liteMs", info.liteMs)
            .put("split", info.split)
            .put("boost", info.touchBoost)
            .put("stream", info.stream)
            .put("chunk", info.chunkWanted)
            .put("quiet", info.quiet)
            .put("trim", info.trim)
            .put("frameCap", info.frameThrottle)
            .put("memFeat", info.memoryFeatures)
            .put("at", System.currentTimeMillis())
    }

    private fun nullable(value: Double?): Any = value ?: JSONObject.NULL

    /**
     * Same identity [chrome://version] / Settings → WebView uses:
     * [WebView.getCurrentWebViewPackage] plus [WebViewCompat.isMultiProcessEnabled].
     * [WebSettings.getDefaultUserAgent] is the page-visible Chrome token fallback.
     */
    private fun webViewMultiProcess(): Any = try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROCESS)) {
            WebViewCompat.isMultiProcessEnabled()
        } else {
            JSONObject.NULL
        }
    } catch (_: Exception) {
        JSONObject.NULL
    }

    private fun defaultUserAgent(context: Context): String? = try {
        WebSettings.getDefaultUserAgent(context)
    } catch (_: Exception) {
        null
    }

    private fun geckoVersionName(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(BrowserEngine.GECKO_ENGINE_PACKAGE, 0).versionName
    }.getOrNull()

    /**
     * Never call [Context.getDisplay]: a Service context throws
     * [UnsupportedOperationException] and kills the overlay on the next touch.
     * [View.getDisplay] is also unsafe on some OEMs (falls through to the context).
     */
    private fun displayRefreshHz(context: Context, @Suppress("UNUSED_PARAMETER") view: View?): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return 0
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay ?: return 0
            val hz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    display.mode?.refreshRate ?: display.refreshRate
                } catch (_: Exception) {
                    display.refreshRate
                }
            } else {
                @Suppress("DEPRECATION")
                display.refreshRate
            }
            hz.roundToInt().coerceIn(0, 240)
        } catch (_: Exception) {
            0
        }
    }

    /** This process (UI + WebView binder). Renderer work is out-of-process on Chromium. */
    private fun readAppCpuPercent(cores: Int): Double? {
        val ticks = readSelfCpuTicks() ?: return null
        val now = SystemClock.elapsedRealtime()
        val prev = lastSelf
        lastSelf = SelfTicks(ticks, now)
        if (prev == null) return null
        val dtMs = (now - prev.atMs).coerceAtLeast(1L)
        val dTicks = (ticks - prev.ticks).coerceAtLeast(0L)
        val hz = 100.0
        val busySec = dTicks / hz
        val wallSec = dtMs / 1000.0
        val pct = busySec / wallSec / cores.toDouble() * 100.0
        return ((pct.coerceIn(0.0, 100.0) * 10).toInt()) / 10.0
    }

    private fun readSelfCpuTicks(): Long? {
        return try {
            val line = File("/proc/self/stat").bufferedReader().use { it.readLine() } ?: return null
            val close = line.lastIndexOf(')')
            if (close < 0) return null
            val parts = line.substring(close + 1).trim().split(Regex("\\s+"))
            val utime = parts.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = parts.getOrNull(12)?.toLongOrNull() ?: return null
            utime + stime
        } catch (_: Exception) {
            null
        }
    }

    private fun readSysCpuPercent(): Double? {
        val sample = readProcStat() ?: return null
        val prev = lastSys
        lastSys = sample
        if (prev == null) return null
        val idleDelta = (sample.idle - prev.idle).coerceAtLeast(0)
        val totalDelta = (sample.total - prev.total).coerceAtLeast(0)
        if (totalDelta == 0L) return null
        val busy = 1.0 - (idleDelta.toDouble() / totalDelta.toDouble())
        return ((busy * 1000).toInt()) / 10.0
    }

    private fun readProcStat(): CpuTicks? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            if (!line.startsWith("cpu ")) return null
            val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return null
            val idle = nums[3] + nums.getOrElse(4) { 0L }
            CpuTicks(idle = idle, total = nums.sum())
        } catch (_: Exception) {
            null
        }
    }
}
