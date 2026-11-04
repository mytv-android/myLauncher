package com.github.mytv.feature.organizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Reads the current LAN address from ConnectivityManager.
 *
 * Network capabilities supply the transport type (Ethernet / Wi-Fi / other) and VPN
 * state; link addresses supply the IPv4 address. Tunnel interfaces are rejected by
 * [LanAddressPicker], as is the 198.18.0.0/15 proxy range.
 */
class LanAddressProvider(private val context: Context) {
    fun discover(): LanAddress? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val candidates = ArrayList<LanCandidate>()
        for (network in manager.allNetworks) {
            val capabilities = try {
                manager.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            } ?: continue
            val link = try {
                manager.getLinkProperties(network)
            } catch (_: Exception) {
                null
            } ?: continue
            val transport = transportOf(capabilities)
            val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val interfaceName = link.interfaceName ?: "unknown"
            for (address in link.linkAddresses) {
                val inet = address.address
                if (inet !is Inet4Address) continue
                candidates += LanCandidate(
                    interfaceName = interfaceName,
                    address = inet.hostAddress ?: continue,
                    transport = transport,
                    isVpn = isVpn,
                )
            }
            collectFromInterface(interfaceName, transport, candidates)
        }
        if (candidates.isNotEmpty()) {
            return LanAddressPicker.pick(candidates)
        }
        // Some TV firmwares do not expose link addresses through ConnectivityManager.
        return LanAddressPicker.pick(candidatesFromInterfaces())
    }

    private fun collectFromInterface(
        interfaceName: String,
        transport: LanTransport,
        out: MutableList<LanCandidate>,
    ) {
        try {
            val networkInterface = java.net.NetworkInterface.getByName(interfaceName) ?: return
            for (address in networkInterface.inetAddresses) {
                val inet = address
                if (inet !is Inet4Address) continue
                out += LanCandidate(
                    interfaceName = interfaceName,
                    address = inet.hostAddress ?: continue,
                    transport = transport,
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun candidatesFromInterfaces(): List<LanCandidate> {
        val out = ArrayList<LanCandidate>()
        try {
            for (networkInterface in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!networkInterface.isUp) continue
                val name = networkInterface.name
                val transport = when {
                    name.startsWith("eth") -> LanTransport.ETHERNET
                    name.startsWith("wlan") || name.startsWith("ap") -> LanTransport.WIFI
                    else -> LanTransport.OTHER
                }
                for (address in networkInterface.inetAddresses) {
                    if (address !is Inet4Address) continue
                    out += LanCandidate(
                        interfaceName = name,
                        address = address.hostAddress ?: continue,
                        transport = transport,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun transportOf(capabilities: NetworkCapabilities): LanTransport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanTransport.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanTransport.WIFI
        else -> LanTransport.OTHER
    }

    fun networkTypeName(address: LanAddress?): String = when (address?.transport) {
        LanTransport.ETHERNET -> "ethernet"
        LanTransport.WIFI -> "wifi"
        else -> "none"
    }

    @Suppress("unused")
    private fun addressesOf(link: LinkProperties): List<InetAddress> =
        link.linkAddresses.map { it.address }
}
