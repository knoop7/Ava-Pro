package com.example.ava.fleet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object FleetNetwork {
    fun getLocalIpAddress(context: Context): String? {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val onWifiOrEthernet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = connectivityManager.getNetworkCapabilities(
                    connectivityManager.activeNetwork,
                )
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            } else {
                @Suppress("DEPRECATION")
                val type = connectivityManager.activeNetworkInfo?.type
                @Suppress("DEPRECATION")
                type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET
            }

            if (onWifiOrEthernet) {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun buildAccessUrl(ip: String?, port: Int): String {
        val host = ip?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        return "http://$host:$port"
    }
}
