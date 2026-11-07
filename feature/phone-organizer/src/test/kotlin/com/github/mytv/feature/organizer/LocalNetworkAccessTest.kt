package com.github.mytv.feature.organizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table only — the granted branch needs a real Context and is
 * covered on device (ADR-005).
 */
class LocalNetworkAccessTest {

    @Test
    fun `permission is the nearby devices group member`() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkAccess.PERMISSION)
    }

    @Test
    fun `not enforced below api 37`() {
        assertFalse(LocalNetworkAccess.isEnforced(23))
        assertFalse(LocalNetworkAccess.isEnforced(34))
        assertFalse(LocalNetworkAccess.isEnforced(36))
    }

    @Test
    fun `enforced from api 37`() {
        assertTrue(LocalNetworkAccess.isEnforced(37))
        assertTrue(LocalNetworkAccess.isEnforced(38))
    }

    @Test
    fun `never asks where the permission is not enforced`() {
        assertFalse(LocalNetworkAccess.shouldRequest(23, granted = false))
        assertFalse(LocalNetworkAccess.shouldRequest(36, granted = false))
    }

    @Test
    fun `asks on api 37 when not granted`() {
        assertTrue(LocalNetworkAccess.shouldRequest(37, granted = false))
        assertTrue(LocalNetworkAccess.shouldRequest(38, granted = false))
    }

    @Test
    fun `does not ask again once granted`() {
        assertFalse(LocalNetworkAccess.shouldRequest(37, granted = true))
    }
}
