package com.github.mytv.feature.organizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    @Test
    fun skipsVpnTunAndProxyRange() {
        val candidates = listOf(
            LanCandidate("tun0", "10.8.0.2", LanTransport.OTHER, isTun = true),
            LanCandidate("ppp0", "10.9.0.2", LanTransport.OTHER),
            LanCandidate("wlan0", "198.18.0.4", LanTransport.WIFI),
            LanCandidate("wlan0", "198.19.4.4", LanTransport.WIFI),
            LanCandidate("eth0", "10.0.0.9", LanTransport.ETHERNET, isVpn = true),
            LanCandidate("wlan0", "192.168.1.30", LanTransport.WIFI),
        )
        val picked = LanAddressPicker.pick(candidates)
        assertEquals("192.168.1.30", picked?.address)
        assertTrue(LanAddressPicker.isProxyRange("198.18.0.1"))
        assertTrue(LanAddressPicker.isProxyRange("198.19.255.255"))
        assertFalse(LanAddressPicker.isProxyRange("198.20.0.1"))
    }

    @Test
    fun prefersEthernetOverWifi() {
        val candidates = listOf(
            LanCandidate("wlan0", "192.168.1.20", LanTransport.WIFI),
            LanCandidate("eth0", "192.168.1.21", LanTransport.ETHERNET),
        )
        val picked = LanAddressPicker.pick(candidates)
        assertEquals("192.168.1.21", picked?.address)
        assertEquals(LanTransport.ETHERNET, picked?.transport)
    }

    @Test
    fun noUsableCandidateMeansNoService() {
        val candidates = listOf(
            LanCandidate("lo", "127.0.0.1", LanTransport.OTHER),
            LanCandidate("wlan0", "169.254.10.5", LanTransport.WIFI),
            LanCandidate("wlan0", "0.0.0.0", LanTransport.WIFI),
            LanCandidate("tun0", "192.168.1.4", LanTransport.WIFI, isTun = true),
            LanCandidate("wlan0", "198.18.1.9", LanTransport.WIFI),
        )
        assertNull(LanAddressPicker.pick(candidates))
        assertNull(LanAddressPicker.pick(emptyList()))
    }

    @Test
    fun wifiWinsWhenNoEthernetExists() {
        val candidates = listOf(
            LanCandidate("wlan0", "192.168.0.40", LanTransport.WIFI),
            LanCandidate("rmnet0", "10.20.30.40", LanTransport.OTHER),
        )
        val picked = LanAddressPicker.pick(candidates)
        assertEquals("192.168.0.40", picked?.address)
        assertEquals(LanTransport.WIFI, picked?.transport)
    }
}
