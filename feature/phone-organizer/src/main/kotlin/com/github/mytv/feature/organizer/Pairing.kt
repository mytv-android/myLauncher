package com.github.mytv.feature.organizer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** How a browser identifies itself when asking for a session. */
enum class CredentialKind { TOKEN, CODE }

data class PendingConnection(
    val id: String,
    val device: String,
    val sourceIp: String,
    val createdAt: Long,
    val expiresAt: Long,
)

data class PairingSession(
    val id: String,
    val device: String,
    val createdAt: Long,
    val lastActivity: Long,
    val activeTransfers: Int,
)

sealed class PairingRequestResult {
    data class Pending(val request: PendingConnection) : PairingRequestResult()
    data class Unauthorized(val message: String) : PairingRequestResult()
    data class Blocked(val retryAfterMs: Long) : PairingRequestResult()
}

sealed class PairingResolution {
    data class Allowed(val session: PairingSession) : PairingResolution()
    data object Denied : PairingResolution()
    data object LimitReached : PairingResolution()
    data object Unknown : PairingResolution()
}

/**
 * Session and one-time credential state for the phone/PC service (§14).
 *
 * Rules:
 *  - the QR token and the six digit pairing code are both single use; accepting or
 *    denying a connection rotates both immediately;
 *  - five wrong pairing codes in a row rotate the code at once and answer 429 to that
 *    source address for 30 seconds;
 *  - a session expires after ten minutes without a request; a running transfer counts
 *    as activity;
 *  - at most four sessions may exist at once;
 *  - the Host header must equal `<address>:<port>`.
 *
 * Everything is driven by an injectable clock so the rules are testable on the JVM.
 */
class PairingStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val blockDurationMs: Long = BLOCK_DURATION_MS,
    private val maxSessions: Int = MAX_SESSIONS,
    private val maxCodeFailures: Int = MAX_CODE_FAILURES,
) {
    companion object {
        const val IDLE_TIMEOUT_MS = 10L * 60 * 1000
        const val REQUEST_TIMEOUT_MS = 60L * 1000
        const val BLOCK_DURATION_MS = 30L * 1000
        const val MAX_SESSIONS = 4
        const val MAX_CODE_FAILURES = 5
        const val TOKEN_BYTES = 16

        fun hostValid(hostHeader: String?, address: String, port: Int): Boolean {
            if (hostHeader.isNullOrBlank()) return false
            return hostHeader.trim().equals("$address:$port", ignoreCase = true)
        }
    }

    var token: String = newToken()
        private set
    var code: String = newCode()
        private set

    private val failures = HashMap<String, Int>()
    private val blockedUntil = HashMap<String, Long>()
    private val pending = LinkedHashMap<String, PendingConnection>()
    private val sessions = LinkedHashMap<String, PairingSession>()

    @Synchronized
    fun request(
        kind: CredentialKind,
        credential: String,
        sourceIp: String,
        device: String,
    ): PairingRequestResult {
        val now = clock()
        purge(now)

        val blocked = blockedUntil[sourceIp]
        if (blocked != null && blocked > now) {
            return PairingRequestResult.Blocked(blocked - now)
        }

        when (kind) {
            CredentialKind.TOKEN -> {
                if (!constantEquals(credential, token)) {
                    return PairingRequestResult.Unauthorized("invalid token")
                }
                failures.remove(sourceIp)
            }
            CredentialKind.CODE -> {
                if (!constantEquals(credential.filter { it.isDigit() }, code)) {
                    val count = (failures[sourceIp] ?: 0) + 1
                    if (count >= maxCodeFailures) {
                        failures.remove(sourceIp)
                        blockedUntil[sourceIp] = now + blockDurationMs
                        rotateCredentials()
                    } else {
                        failures[sourceIp] = count
                    }
                    return PairingRequestResult.Unauthorized("invalid code")
                }
                failures.remove(sourceIp)
            }
        }

        val request = PendingConnection(
            id = UUID.randomUUID().toString(),
            device = device.take(96),
            sourceIp = sourceIp,
            createdAt = now,
            expiresAt = now + requestTimeoutMs,
        )
        pending[request.id] = request
        return PairingRequestResult.Pending(request)
    }

    @Synchronized
    fun decide(requestId: String, allow: Boolean): PairingResolution {
        val now = clock()
        purge(now)
        val request = pending.remove(requestId) ?: return PairingResolution.Unknown
        if (request.expiresAt <= now) return PairingResolution.Unknown

        if (allow && sessions.size >= maxSessions) {
            rotateCredentials()
            return PairingResolution.LimitReached
        }
        rotateCredentials()
        if (!allow) return PairingResolution.Denied

        val session = PairingSession(
            id = UUID.randomUUID().toString(),
            device = request.device,
            createdAt = now,
            lastActivity = now,
            activeTransfers = 0,
        )
        sessions[session.id] = session
        return PairingResolution.Allowed(session)
    }

    @Synchronized
    fun cancelPending(requestId: String) {
        pending.remove(requestId)
    }

    @Synchronized
    fun pendingRequests(): List<PendingConnection> {
        purge(clock())
        return pending.values.toList()
    }

    @Synchronized
    fun sessions(): List<PairingSession> {
        purge(clock())
        return sessions.values.toList()
    }

    @Synchronized
    fun session(sessionId: String?): PairingSession? {
        if (sessionId.isNullOrEmpty()) return null
        purge(clock())
        return sessions[sessionId]
    }

    @Synchronized
    fun touch(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        val now = clock()
        purge(now)
        val session = sessions[sessionId] ?: return
        sessions[sessionId] = session.copy(lastActivity = now)
    }

    @Synchronized
    fun beginTransfer(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        val now = clock()
        purge(now)
        val session = sessions[sessionId] ?: return
        sessions[sessionId] = session.copy(
            lastActivity = now,
            activeTransfers = session.activeTransfers + 1,
        )
    }

    @Synchronized
    fun endTransfer(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        val now = clock()
        val session = sessions[sessionId] ?: return
        sessions[sessionId] = session.copy(
            lastActivity = now,
            activeTransfers = (session.activeTransfers - 1).coerceAtLeast(0),
        )
    }

    @Synchronized
    fun disconnect(sessionId: String?): Boolean {
        if (sessionId.isNullOrEmpty()) return false
        return sessions.remove(sessionId) != null
    }

    @Synchronized
    fun disconnectAll() {
        sessions.clear()
        pending.clear()
    }

    @Synchronized
    fun rotateCredentials() {
        val oldToken = token
        val oldCode = code
        token = newToken()
        code = newCode()
        while (token == oldToken) token = newToken()
        while (code == oldCode) code = newCode()
        failures.clear()
        pending.clear()
    }

    private fun purge(now: Long) {
        val pendingIterator = pending.entries.iterator()
        while (pendingIterator.hasNext()) {
            if (pendingIterator.next().value.expiresAt <= now) pendingIterator.remove()
        }
        val sessionIterator = sessions.entries.iterator()
        while (sessionIterator.hasNext()) {
            val session = sessionIterator.next().value
            if (session.activeTransfers > 0) continue
            if (now - session.lastActivity >= idleTimeoutMs) sessionIterator.remove()
        }
        val blockIterator = blockedUntil.entries.iterator()
        while (blockIterator.hasNext()) {
            if (blockIterator.next().value <= now) blockIterator.remove()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        }
        return builder.toString()
    }

    private fun newCode(): String =
        String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )
}
