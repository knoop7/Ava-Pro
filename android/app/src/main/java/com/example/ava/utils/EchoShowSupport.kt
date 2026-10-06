package com.example.ava.utils

import android.content.Context
import android.os.Build
import com.example.ava.permissions.OverlayPermission

object EchoShowSupport {

    private var _isEchoShowDevice: Boolean? = null

    /**
     * Amazon Echo display codenames. LineageOS ports keep `Build.DEVICE` (e.g. `crown`)
     * while `Build.PRODUCT` becomes `lineage_crown`; MODEL may no longer say "Echo".
     */
    private val ECHO_SHOW_CODENAMES = listOf("crown", "checkers", "cronos", "rook")

    fun isEchoShowDevice(): Boolean {
        if (_isEchoShowDevice != null) return _isEchoShowDevice!!

        _isEchoShowDevice = matchesBuild(
            model = Build.MODEL,
            board = Build.BOARD,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            manufacturer = Build.MANUFACTURER,
        )

        return _isEchoShowDevice!!
    }

    fun getMinBrightness(): Int {
        return if (isEchoShowDevice()) 10 else 0
    }

    /**
     * Fire OS hides the overlay switch; the Lineage 18.1 ports disable it via
     * `ro.config.low_ram`. Root / Shizuku `appops` is the only in-app path.
     */
    fun grantOverlayPermissionIfNeeded(context: Context): Boolean {
        if (!isEchoShowDevice()) return false
        if (OverlayPermission.isGranted(context)) return true
        return OverlayPermission.grantViaPrivilegedShell(context)
    }

    internal fun matchesBuild(
        model: String?,
        board: String?,
        device: String?,
        product: String? = null,
        manufacturer: String? = null,
    ): Boolean {
        return try {
            val modelLower = model.orEmpty().lowercase()
            val boardLower = board.orEmpty().lowercase()
            val deviceLower = device.orEmpty().lowercase()
            val productLower = product.orEmpty().lowercase()
            val manufacturerLower = manufacturer.orEmpty().lowercase()
            val haystacks = listOf(modelLower, boardLower, deviceLower, productLower)

            ECHO_SHOW_CODENAMES.any { codename ->
                haystacks.any { it.contains(codename) }
            } ||
                modelLower.contains("amazon") ||
                (modelLower.contains("echo") && modelLower.contains("show")) ||
                (manufacturerLower.contains("amazon") &&
                    (modelLower.contains("echo") || deviceLower.contains("echo") ||
                        productLower.contains("echo")))
        } catch (_: Exception) {
            false
        }
    }
}
