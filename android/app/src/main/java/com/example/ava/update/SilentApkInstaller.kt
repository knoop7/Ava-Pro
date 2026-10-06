package com.example.ava.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged `pm install` (Shizuku / root) when available, then PackageInstaller
 * (silent for Device Owner), then confirmation UI.
 *
 * Device Owner sessions set [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED].
 * targetSdk 34+ otherwise shows a confirm dialog even for the owner. Active admin
 * stays on the confirm path; that role cannot install packages.
 *
 * Order when [canAttemptSilent]: Shizuku → Root → PackageInstaller (SUCCESS only).
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] is not treated as success so Shizuku/root
 * are not skipped after a no-op confirm hand-off (kiosk / Portal).
 *
 * Without privileged install: PackageInstaller → PI confirm or Intent UI.
 */
object SilentApkInstaller {
    private const val TAG = "SilentApkInstaller"
    private const val ACTION_INSTALL_RESULT = "com.example.ava.update.ACTION_PACKAGE_INSTALL_RESULT"

    enum class Method {
        DEVICE_OWNER,
        PACKAGE_INSTALLER,
        SHIZUKU,
        ROOT,
        INTERACTIVE,
    }

    data class Result(
        val success: Boolean,
        val method: Method?,
        val message: String = "",
        val silent: Boolean = false,
    )

    private data class PackageInstallerOutcome(
        val result: Result,
        val pendingConfirm: Intent? = null,
    )

    fun canAttemptSilent(context: Context): Boolean {
        return ScreenControlUtils.isDeviceOwner(context) ||
            ShizukuUtils.isShizukuPermissionGranted() ||
            RootUtils.isRootAvailable()
    }

    fun describeCapability(context: Context): String = when {
        ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
        ScreenControlUtils.isDeviceOwner(context) -> "device_owner"
        RootUtils.isRootAvailable() -> "root"
        ScreenControlUtils.isDeviceAdminActive(context) -> "device_admin_interactive_only"
        else -> "interactive"
    }

    fun install(context: Context, apkFile: File, packageName: String): Result {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            return Result(false, null, "APK missing or empty")
        }

        val privileged = canAttemptSilent(context)

        if (privileged && ShizukuUtils.isShizukuPermissionGranted()) {
            val ok = runCatching { ShizukuUtils.installApk(apkFile) }.getOrDefault(false)
            if (ok) {
                return Result(true, Method.SHIZUKU, "pm install via Shizuku", silent = true)
            }
            Log.w(TAG, "Shizuku install failed, trying fallbacks")
        }

        if (privileged && RootUtils.isRootAvailable()) {
            val root = installViaRootStdin(apkFile)
            if (root.success) return root
            Log.w(TAG, "Root install failed: ${root.message}")
        }

        val pi = installViaPackageInstaller(context, apkFile, packageName)
        if (pi.result.success) return pi.result
        if (pi.result.message.isNotBlank()) {
            Log.w(TAG, "PackageInstaller path: ${pi.result.message}")
        }

        val pending = pi.pendingConfirm
        if (pending != null) {
            return launchPackageInstallerConfirm(context, pending)
        }

        return installInteractive(context, apkFile)
    }

    private fun launchPackageInstallerConfirm(context: Context, confirm: Intent): Result {
        return try {
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.applicationContext.startActivity(confirm)
            Result(
                success = true,
                method = Method.PACKAGE_INSTALLER,
                message = "Opened PackageInstaller confirmation",
                silent = false,
            )
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller confirm activity failed", e)
            Result(false, Method.PACKAGE_INSTALLER, e.message ?: "confirm_activity_failed")
        }
    }

    private fun installViaRootStdin(apkFile: File): Result {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "pm install -r -d -S ${apkFile.length()}")
            )
            apkFile.inputStream().use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                }
            }
            val code = process.waitFor()
            if (code == 0) {
                Result(true, Method.ROOT, "pm install via root", silent = true)
            } else {
                Result(false, Method.ROOT, "pm install exit=$code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root stdin install error", e)
            Result(false, Method.ROOT, e.message ?: "root install error")
        }
    }

    private data class SessionStatus(
        val status: Int,
        val message: String,
        val confirmIntent: Intent?,
    )

    private fun installViaPackageInstaller(
        context: Context,
        apkFile: File,
        packageName: String,
    ): PackageInstallerOutcome {
        val appContext = context.applicationContext
        val installer = appContext.packageManager.packageInstaller
        val isOwner = ScreenControlUtils.isDeviceOwner(appContext)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (isOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        val latch = CountDownLatch(1)
        val statusRef = AtomicReference<SessionStatus?>(null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val confirm = extractConfirmIntent(intent)
                statusRef.set(SessionStatus(status, message, confirm))
                latch.countDown()
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                }
            }
        }

        return try {
            val filter = IntentFilter(ACTION_INSTALL_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }

            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(appContext.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pi = PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }

            val finished = latch.await(120, TimeUnit.SECONDS)
            if (!finished) {
                return PackageInstallerOutcome(
                    Result(false, Method.PACKAGE_INSTALLER, "PackageInstaller timed out"),
                )
            }
            val outcome = statusRef.get()
                ?: return PackageInstallerOutcome(
                    Result(false, Method.PACKAGE_INSTALLER, "PackageInstaller no status"),
                )
            when (outcome.status) {
                PackageInstaller.STATUS_SUCCESS ->
                    PackageInstallerOutcome(
                        Result(
                            true,
                            if (isOwner) Method.DEVICE_OWNER else Method.PACKAGE_INSTALLER,
                            "PackageInstaller ok",
                            silent = true,
                        ),
                    )
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = outcome.confirmIntent
                    if (confirm == null) {
                        PackageInstallerOutcome(
                            Result(
                                false,
                                Method.PACKAGE_INSTALLER,
                                "User confirmation required but EXTRA_INTENT missing",
                            ),
                        )
                    } else {
                        PackageInstallerOutcome(
                            Result(
                                false,
                                Method.PACKAGE_INSTALLER,
                                "pending_user_action",
                            ),
                            pendingConfirm = confirm,
                        )
                    }
                }
                else ->
                    PackageInstallerOutcome(
                        Result(
                            false,
                            Method.PACKAGE_INSTALLER,
                            "PackageInstaller status=${outcome.status} ${outcome.message}",
                        ),
                    )
            }
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed", e)
            if (sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
            }
            runCatching { appContext.unregisterReceiver(receiver) }
            PackageInstallerOutcome(
                Result(false, Method.PACKAGE_INSTALLER, e.message ?: "PackageInstaller error"),
            )
        }
    }

    private fun extractConfirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    private fun installInteractive(context: Context, apkFile: File): Result {
        return try {
            val cleanupMs = if (apkFile.name.startsWith("gecko-")) null else 60_000L
            AppUpdater.installApkFile(context, apkFile, cleanupApksAfterMs = cleanupMs)
            Result(
                success = true,
                method = Method.INTERACTIVE,
                message = "Opened system installer (user confirmation may be required)",
                silent = false,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Interactive install failed", e)
            Result(false, Method.INTERACTIVE, e.message ?: "interactive install failed")
        }
    }
}
