package com.example.ava.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log


class BleScanReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BleScanReceiver"
        
        
        var onScanResult: ((ScanResult) -> Unit)? = null
    }
    
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val results = intent.getParcelableArrayListExtra<ScanResult>(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        )
        
        if (results != null) {
            for (result in results) {
                onScanResult?.invoke(result)
            }
        }
    }
}
