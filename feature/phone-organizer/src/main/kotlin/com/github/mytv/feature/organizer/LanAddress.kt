package com.github.mytv.feature.organizer

/**
 * One LAN address candidate discovered from the system network stack.
 *
 * The pure selection rules live in [LanAddressPicker] so they can be unit tested
 * without Android. VPN tunnels, tun interfaces and the 198.18.0.0/15 proxy range are
 * ignored; Ethernet wins over Wi-Fi and everything else.
 */
data class LanCandidate(
    val interfaceName: String,
    val address: String,
    val transport: LanTransport,
    val isVpn: Boolean = false,
    val isTun: Boolean = false,
)

enum class LanTransport { ETHERNET, WIFI, OTHER }

data class LanAddress(
    val address: String,
    val transport: LanTransport,
    val interfaceName: String,
)

object LanAddressPicker {
    const val PROXY_RANGE_FIRST = 198
    const val PROXY_RANGE_SECOND_FIRST = 18
    const val PROXY_RANGE_SECOND_LAST = 19

    /** Rank used when several usable candidates exist; lower wins. */
    fun rank(transport: LanTransport): Int = when (transport) {
        LanTransport.ETHERNET -> 0
        LanTransport.WIFI -> 1
        LanTransport.OTHER -> 2
    }

    fun isProxyRange(address: String): Boolean {
        val octets = ipv4Octets(address) ?: return false
        return octets[0] == PROXY_RANGE_FIRST &&
            octets[1] in PROXY_RANGE_SECOND_FIRST..PROXY_RANGE_SECOND_LAST
    }

    fun isUsable(candidate: LanCandidate): Boolean {
        if (candidate.transport == LanTransport.OTHER) return false
        if (candidate.isVpn || candidate.isTun) return false
        val name = candidate.interfaceName.lowercase()
        if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("vpn")) return false
        val octets = ipv4Octets(candidate.address) ?: return false
        if (octets[0] == 127) return false
        if (octets[0] == 0) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        if (isProxyRange(candidate.address)) return false
        if (candidate.address == "255.255.255.255") return false
        return true
    }

    /** Returns the preferred address, or null when nothing usable was found. */
    fun pick(candidates: List<LanCandidate>): LanAddress? {
        val usable = candidates.filter(::isUsable)
        val chosen = usable.minWithOrNull(
            compareBy<LanCandidate>(
                { rank(it.transport) },
                { it.interfaceName.lowercase() },
                { it.address },
            ),
        ) ?: return null
        return LanAddress(chosen.address, chosen.transport, chosen.interfaceName)
    }

    private fun ipv4Octets(address: String): IntArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return null
            if (part.any { it !in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }
}
