package com.example.ava.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.example.ava.R

object BluetoothDeviceNames {
    private const val TAG = "BluetoothDeviceNames"

    fun safeAddress(device: BluetoothDevice): String? {
        return try {
            device.address?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            Log.w(TAG, "safeAddress denied", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "safeAddress failed", e)
            null
        }
    }

    fun safeName(device: BluetoothDevice, fallback: String): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: fallback
        } catch (e: SecurityException) {
            Log.w(TAG, "safeName denied", e)
            fallback
        } catch (e: Exception) {
            Log.w(TAG, "safeName failed", e)
            fallback
        }
    }

    fun unknownDiscoveredLabel(context: Context, address: String): String {
        val suffix = address.takeLast(5)
        return "${context.getString(R.string.settings_bluetooth_unknown_device)} ($suffix)"
    }

    fun unknownBondedLabel(context: Context): String {
        return context.getString(R.string.settings_bluetooth_unknown_device)
    }

    fun isUnknownDeviceLabel(context: Context, name: String): Boolean {
        val unknown = context.getString(R.string.settings_bluetooth_unknown_device)
        return name == unknown || name.startsWith("$unknown (")
    }
}
