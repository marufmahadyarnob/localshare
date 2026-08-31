package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class WifiNetworkState(
    val isConnected: Boolean,
    val ipv4Address: String?,
    val isWifi: Boolean
)

class NetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Resolves the primary active Wi-Fi IPv4 address.
     * Excludes loopback (127.0.0.1), localhost, inactive interfaces, and link-local addresses.
     */
    fun getActiveWifiIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First look specifically for wlan / wifi interfaces
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("eth") || name.contains("ap") || name.contains("wifi")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (isValidLocalIpv4(ip)) {
                                return ip
                            }
                        }
                    }
                }
            }

            // Fallback to any active non-loopback IPv4 interface (excluding cellular rmnet if possible)
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                if (intf.name.lowercase().contains("rmnet") || intf.name.lowercase().contains("pdp")) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (isValidLocalIpv4(ip)) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun isValidLocalIpv4(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "0.0.0.0" || ip.startsWith("169.254.")) {
            return false
        }
        // Standard private IPv4 ranges: 192.168.x.x, 10.x.x.x, 172.16.x.x - 172.31.x.x or custom local subnets
        return true
    }

    /**
     * Checks if active network capability is Wi-Fi or Ethernet
     */
    fun isConnectedToLocalNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Real-time network state flow
     */
    val networkState: Flow<WifiNetworkState> = callbackFlow {
        fun sendCurrentState() {
            val isWifi = isConnectedToLocalNetwork()
            val ip = getActiveWifiIpAddress()
            val isConnected = isWifi && ip != null
            trySend(WifiNetworkState(isConnected = isConnected, ipv4Address = ip, isWifi = isWifi))
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                sendCurrentState()
            }

            override fun onLost(network: Network) {
                sendCurrentState()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                sendCurrentState()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                sendCurrentState()
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        sendCurrentState()

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
    }.distinctUntilChanged()
}
