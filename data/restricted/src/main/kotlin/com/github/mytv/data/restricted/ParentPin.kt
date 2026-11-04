package com.github.mytv.data.restricted

import java.security.MessageDigest
import java.security.SecureRandom

/** Tiny key/value persistence contract so the PIN policy stays JVM-testable. */
interface PinStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

sealed interface PinVerifyResult {
    data class Success(val unlockedUntil: Long) : PinVerifyResult
    data class Wrong(val attemptsLeft: Int) : PinVerifyResult
    data class Locked(val lockedUntil: Long, val remainingMillis: Long) : PinVerifyResult
    data object NoPin : PinVerifyResult
}

data class PinLockState(
    val hasPin: Boolean,
    val failedAttempts: Int,
    val lockedUntil: Long,
    val unlockedUntil: Long,
    val locked: Boolean,
    val unlocked: Boolean,
)

/**
 * Parent PIN policy shared by kids and elders modes.
 *
 * The PIN is four digits or four D-pad directions. Storage is a random 16-byte salt plus
 * SHA-256(salt || canonical pin); the plaintext is never written. Five consecutive wrong
 * entries lock verification for 60 seconds. A correct entry unlocks settings for 10
 * minutes; HOME, the screensaver and screen off relock immediately. Pausing a restricted
 * mode is persisted and is cleared when the screensaver starts, the screen turns off or
 * the process starts again after a reboot.
 */
class ParentPin(
    private val store: PinStore,
    private val clock: Clock = Clock.system(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun hasPin(): Boolean = store.get(KEY_SALT) != null && store.get(KEY_HASH) != null

    fun set(pin: String): Boolean {
        val canonical = canonicalPin(pin) ?: return false
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = hash(salt, canonical)
        store.put(KEY_SALT, Base64Codec.encode(salt))
        store.put(KEY_HASH, Base64Codec.encode(hash))
        store.put(KEY_FAILED, "0")
        store.put(KEY_LOCKED_UNTIL, "0")
        store.put(KEY_UNLOCKED_UNTIL, "0")
        store.put(KEY_PAUSED_MODE, null)
        return true
    }

    fun verify(pin: String, nowMillis: Long): PinVerifyResult {
        val salt = store.get(KEY_SALT)?.let(Base64Codec::decode) ?: return PinVerifyResult.NoPin
        val expected = store.get(KEY_HASH)?.let(Base64Codec::decode) ?: return PinVerifyResult.NoPin
        val lockedUntil = store.get(KEY_LOCKED_UNTIL)?.toLongOrNull() ?: 0L
        if (lockedUntil > nowMillis) {
            return PinVerifyResult.Locked(lockedUntil, lockedUntil - nowMillis)
        }
        if (lockedUntil != 0L) {
            store.put(KEY_LOCKED_UNTIL, "0")
        }
        val canonical = canonicalPin(pin)
        val matches = canonical != null && MessageDigest.isEqual(expected, hash(salt, canonical))
        if (!matches) {
            val attempts = (store.get(KEY_FAILED)?.toIntOrNull() ?: 0) + 1
            if (attempts >= MAX_ATTEMPTS) {
                val until = nowMillis + LOCKOUT_MILLIS
                store.put(KEY_FAILED, "0")
                store.put(KEY_LOCKED_UNTIL, until.toString())
                return PinVerifyResult.Locked(until, LOCKOUT_MILLIS)
            }
            store.put(KEY_FAILED, attempts.toString())
            return PinVerifyResult.Wrong(MAX_ATTEMPTS - attempts)
        }
        store.put(KEY_FAILED, "0")
        store.put(KEY_LOCKED_UNTIL, "0")
        val until = nowMillis + UNLOCK_MILLIS
        store.put(KEY_UNLOCKED_UNTIL, until.toString())
        return PinVerifyResult.Success(until)
    }

    fun verify(pin: String): PinVerifyResult = verify(pin, clock.nowMillis())

    fun lockState(nowMillis: Long): PinLockState {
        val hasPin = hasPin()
        val lockedUntil = store.get(KEY_LOCKED_UNTIL)?.toLongOrNull() ?: 0L
        val unlockedUntil = store.get(KEY_UNLOCKED_UNTIL)?.toLongOrNull() ?: 0L
        val locked = hasPin && lockedUntil > nowMillis
        return PinLockState(
            hasPin = hasPin,
            failedAttempts = store.get(KEY_FAILED)?.toIntOrNull() ?: 0,
            lockedUntil = lockedUntil,
            unlockedUntil = unlockedUntil,
            locked = locked,
            unlocked = hasPin && !locked && unlockedUntil > nowMillis,
        )
    }

    fun lockState(): PinLockState = lockState(clock.nowMillis())

    fun isUnlocked(nowMillis: Long): Boolean = lockState(nowMillis).unlocked

    fun clear() {
        store.put(KEY_SALT, null)
        store.put(KEY_HASH, null)
        store.put(KEY_FAILED, null)
        store.put(KEY_LOCKED_UNTIL, null)
        store.put(KEY_UNLOCKED_UNTIL, null)
        store.put(KEY_PAUSED_MODE, null)
    }

    fun onHome(nowMillis: Long) {
        store.put(KEY_UNLOCKED_UNTIL, "0")
    }

    fun onHome() = onHome(clock.nowMillis())

    /** Relocks and returns the paused mode that becomes active again, if any. */
    fun onScreenOff(nowMillis: Long): String? {
        store.put(KEY_UNLOCKED_UNTIL, "0")
        return resumePausedMode()
    }

    fun onScreenOff(): String? = onScreenOff(clock.nowMillis())

    /** Relocks and returns the paused mode that becomes active again, if any. */
    fun onDream(nowMillis: Long): String? {
        store.put(KEY_UNLOCKED_UNTIL, "0")
        return resumePausedMode()
    }

    fun onDream(): String? = onDream(clock.nowMillis())

    /** Called at process start: the unlock window does not survive a reboot. */
    fun restoreAfterReboot(nowMillis: Long): String? {
        store.put(KEY_UNLOCKED_UNTIL, "0")
        return resumePausedMode()
    }

    fun restoreAfterReboot(): String? = restoreAfterReboot(clock.nowMillis())

    fun pauseMode(mode: String) {
        store.put(KEY_PAUSED_MODE, mode)
    }

    fun pausedMode(): String? = store.get(KEY_PAUSED_MODE)

    fun resumePausedMode(): String? {
        val mode = store.get(KEY_PAUSED_MODE)
        if (mode != null) {
            store.put(KEY_PAUSED_MODE, null)
        }
        return mode
    }

    private fun hash(salt: ByteArray, canonicalPin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(canonicalPin.toByteArray(Charsets.UTF_8))
        }

    private companion object {
        const val PIN_LENGTH = 4
        const val SALT_BYTES = 16
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 60 * 1000L
        const val UNLOCK_MILLIS = 10 * 60 * 1000L

        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILED = "pin_failed"
        const val KEY_LOCKED_UNTIL = "pin_locked_until"
        const val KEY_UNLOCKED_UNTIL = "pin_unlocked_until"
        const val KEY_PAUSED_MODE = "pin_paused_mode"
    }
}

/**
 * Canonical form: four digits ("1234") or four direction letters ("UUDD").
 * Accepts full direction names separated by spaces, commas or dashes.
 */
internal fun canonicalPin(raw: String): String? {
    val tokens = raw.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    if (tokens.size == 1) {
        val token = tokens[0]
        if (token.length != 4) return null
        if (token.all { it in '0'..'9' }) return token
        val directions = token.map { directionLetter(it) }
        if (directions.all { it != null }) return directions.joinToString("") { it.toString() }
        return null
    }
    if (tokens.size != 4) return null
    if (tokens.all { it.length == 1 && it[0] in '0'..'9' }) {
        return tokens.joinToString("")
    }
    val directions = tokens.map { directionWord(it) }
    if (directions.all { it != null }) return directions.joinToString("") { it.toString() }
    return null
}

private fun directionLetter(char: Char): Char? = when (char.uppercaseChar()) {
    'U' -> 'U'
    'D' -> 'D'
    'L' -> 'L'
    'R' -> 'R'
    else -> null
}

private fun directionWord(token: String): Char? = when (token.uppercase()) {
    "UP", "U" -> 'U'
    "DOWN", "D" -> 'D'
    "LEFT", "L" -> 'L'
    "RIGHT", "R" -> 'R'
    else -> null
}
