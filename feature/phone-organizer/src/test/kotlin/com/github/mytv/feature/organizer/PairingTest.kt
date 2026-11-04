package com.github.mytv.feature.organizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private class Clock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun allowed(machine: PairingStateMachine, ip: String = "10.0.0.2"): PairingSession {
        val request = machine.request(CredentialKind.TOKEN, machine.token, ip, "Browser")
        assertTrue(request is PairingRequestResult.Pending)
        val pending = request as PairingRequestResult.Pending
        val resolution = machine.decide(pending.request.id, allow = true)
        assertTrue(resolution is PairingResolution.Allowed)
        return (resolution as PairingResolution.Allowed).session
    }

    @Test
    fun tokenAndCodeAreInvalidatedAfterAllowOrDeny() {
        val machine = PairingStateMachine()
        val firstToken = machine.token
        val allowedRequest = machine.request(CredentialKind.TOKEN, firstToken, "10.0.0.2", "Browser")
        val pending = allowedRequest as PairingRequestResult.Pending
        val allowed = machine.decide(pending.request.id, allow = true)
        assertTrue(allowed is PairingResolution.Allowed)
        assertNotEquals(firstToken, machine.token)
        assertTrue(
            machine.request(CredentialKind.TOKEN, firstToken, "10.0.0.2", "Browser")
                is PairingRequestResult.Unauthorized,
        )

        val firstCode = machine.code
        val deniedRequest = machine.request(CredentialKind.CODE, firstCode, "10.0.0.3", "Browser")
        val deniedPending = deniedRequest as PairingRequestResult.Pending
        val denied = machine.decide(deniedPending.request.id, allow = false)
        assertTrue(denied is PairingResolution.Denied)
        assertNotEquals(firstCode, machine.code)
        assertTrue(
            machine.request(CredentialKind.CODE, firstCode, "10.0.0.3", "Browser")
                is PairingRequestResult.Unauthorized,
        )
    }

    @Test
    fun fiveWrongCodesRotateAndBlockThatSource() {
        val clock = Clock()
        val machine = PairingStateMachine(clock = { clock.now })
        val firstCode = machine.code
        val wrong = if (firstCode == "000000") "111111" else "000000"
        repeat(5) {
            val result = machine.request(CredentialKind.CODE, wrong, "10.0.0.5", "Browser")
            assertTrue(result is PairingRequestResult.Unauthorized)
        }
        assertNotEquals(firstCode, machine.code)
        val blocked = machine.request(CredentialKind.TOKEN, machine.token, "10.0.0.5", "Browser")
        assertTrue(blocked is PairingRequestResult.Blocked)
        assertTrue((blocked as PairingRequestResult.Blocked).retryAfterMs > 0)
        val otherSource = machine.request(CredentialKind.TOKEN, machine.token, "10.0.0.6", "Browser")
        assertTrue(otherSource is PairingRequestResult.Pending)
        clock.advance(PairingStateMachine.BLOCK_DURATION_MS + 1)
        val afterBlock = machine.request(CredentialKind.TOKEN, machine.token, "10.0.0.5", "Browser")
        assertTrue(afterBlock is PairingRequestResult.Pending)
    }

    @Test
    fun sessionsExpireAfterTenIdleMinutesButTransfersKeepThemAlive() {
        val clock = Clock()
        val machine = PairingStateMachine(clock = { clock.now })
        val session = allowed(machine)
        clock.advance(9 * 60 * 1000L)
        assertNotNull(machine.session(session.id))
        machine.beginTransfer(session.id)
        clock.advance(60 * 60 * 1000L)
        assertNotNull(machine.session(session.id))
        machine.endTransfer(session.id)
        assertNotNull(machine.session(session.id))
        clock.advance(PairingStateMachine.IDLE_TIMEOUT_MS)
        assertNull(machine.session(session.id))
    }

    @Test
    fun atMostFourSessionsExist() {
        val machine = PairingStateMachine()
        repeat(4) { index ->
            allowed(machine, "10.0.1.$index")
        }
        assertEquals(4, machine.sessions().size)
        val request = machine.request(CredentialKind.TOKEN, machine.token, "10.0.1.9", "Browser")
        val pending = request as PairingRequestResult.Pending
        val resolution = machine.decide(pending.request.id, allow = true)
        assertTrue(resolution is PairingResolution.LimitReached)
        assertEquals(4, machine.sessions().size)
    }

    @Test
    fun hostHeaderMustMatchAddressAndPort() {
        assertTrue(PairingStateMachine.hostValid("192.168.1.5:51234", "192.168.1.5", 51234))
        assertFalse(PairingStateMachine.hostValid("attacker.example", "192.168.1.5", 51234))
        assertFalse(PairingStateMachine.hostValid("192.168.1.5:51235", "192.168.1.5", 51234))
        assertFalse(PairingStateMachine.hostValid(null, "192.168.1.5", 51234))
    }

    @Test
    fun pendingRequestTimesOutAfterSixtySeconds() {
        val clock = Clock()
        val machine = PairingStateMachine(clock = { clock.now })
        val request = machine.request(CredentialKind.TOKEN, machine.token, "10.0.0.8", "Browser")
        val pending = request as PairingRequestResult.Pending
        clock.advance(PairingStateMachine.REQUEST_TIMEOUT_MS + 1)
        assertTrue(machine.pendingRequests().isEmpty())
        assertTrue(machine.decide(pending.request.id, allow = true) is PairingResolution.Unknown)
    }
}
