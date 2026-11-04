package com.github.mytv.data.restricted

import android.content.Context
import android.content.SharedPreferences
import java.util.TimeZone

enum class RestrictedMode(val wire: String) {
    OFF("off"),
    KIDS("kids"),
    ELDERS("elders"),
    ;

    companion object {
        fun fromWire(value: String?): RestrictedMode {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.wire == normalized } ?: OFF
        }
    }
}

data class RestrictedState(
    val mode: RestrictedMode,
    val allowedPackages: Set<String>,
    val kidsQuotaSeconds: Long,
    val remainingSeconds: Long,
    val paused: Boolean,
    val hasPin: Boolean,
    val pinLockedUntil: Long,
    val unlockedUntil: Long,
) {
    val locked: Boolean
        get() = mode != RestrictedMode.OFF && remainingSeconds <= 0L
}

/**
 * SharedPreferences view of the restricted-mode settings.
 *
 * Keys `restricted_mode`, `restricted_allowed`, `kids_quota_s` and `pin` match the debug
 * `set_pref` contract. A plaintext `pin` value written through ADB is hashed on the next
 * read and then removed, so the plaintext never survives in storage.
 */
class RestrictedRepository(
    context: Context,
    private val clock: Clock = Clock.system(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val pinStore: PinStore = SharedPreferencesPinStore(prefs)

    val parentPin: ParentPin = ParentPin(pinStore, clock)

    fun mode(): RestrictedMode = RestrictedMode.fromWire(prefs.all[KEY_MODE]?.toString())

    fun setMode(mode: RestrictedMode) {
        prefs.edit().putString(KEY_MODE, mode.wire).apply()
    }

    fun allowedPackages(): Set<String> =
        (prefs.all[KEY_ALLOWED]?.toString() ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun setAllowedPackages(packages: Collection<String>) {
        val value = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        prefs.edit().putString(KEY_ALLOWED, value).apply()
    }

    fun addAllowedPackage(packageName: String) {
        setAllowedPackages(allowedPackages() + packageName)
    }

    fun removeAllowedPackage(packageName: String) {
        setAllowedPackages(allowedPackages() - packageName)
    }

    fun isAllowed(packageName: String): Boolean = packageName in allowedPackages()

    fun kidsQuotaSeconds(): Long =
        (prefs.all[KEY_KIDS_QUOTA]?.toString()?.toLongOrNull() ?: 0L).coerceAtLeast(0L)

    fun setKidsQuotaSeconds(seconds: Long) {
        prefs.edit().putString(KEY_KIDS_QUOTA, seconds.coerceAtLeast(0L).toString()).apply()
    }

    fun remainingSeconds(): Long =
        prefs.all[KEY_REMAINING]?.toString()?.toLongOrNull() ?: 0L

    fun setRemainingSeconds(seconds: Long) {
        prefs.edit().putString(KEY_REMAINING, seconds.coerceAtLeast(0L).toString()).apply()
    }

    fun paused(): Boolean =
        prefs.all[KEY_PAUSED]?.toString()?.toBooleanStrictOrNull() ?: false

    fun setPaused(paused: Boolean) {
        prefs.edit().putString(KEY_PAUSED, paused.toString()).apply()
        if (paused) {
            val current = mode()
            if (current != RestrictedMode.OFF) {
                parentPin.pauseMode(current.wire)
            }
        } else {
            parentPin.resumePausedMode()
        }
    }

    fun hasPin(): Boolean {
        importLegacyPlainPin()
        return parentPin.hasPin()
    }

    fun setPin(pin: String): Boolean = parentPin.set(pin)

    fun clearPin() {
        parentPin.clear()
        prefs.edit().remove(KEY_LEGACY_PIN).apply()
    }

    fun pinLockedUntil(): Long = parentPin.lockState().lockedUntil

    fun unlockedUntil(): Long = parentPin.lockState().unlockedUntil

    fun lockState(): PinLockState = parentPin.lockState()

    fun onHome(nowMillis: Long = clock.nowMillis()) {
        parentPin.onHome(nowMillis)
    }

    fun onScreenOff(nowMillis: Long = clock.nowMillis()): RestrictedMode? =
        applyResumedMode(parentPin.onScreenOff(nowMillis))

    fun onDream(nowMillis: Long = clock.nowMillis()): RestrictedMode? =
        applyResumedMode(parentPin.onDream(nowMillis))

    fun restoreAfterReboot(nowMillis: Long = clock.nowMillis()): RestrictedMode? =
        applyResumedMode(parentPin.restoreAfterReboot(nowMillis))

    fun snapshot(): RestrictedState {
        importLegacyPlainPin()
        val pinState = parentPin.lockState()
        return RestrictedState(
            mode = mode(),
            allowedPackages = allowedPackages(),
            kidsQuotaSeconds = kidsQuotaSeconds(),
            remainingSeconds = remainingSeconds(),
            paused = paused(),
            hasPin = pinState.hasPin,
            pinLockedUntil = pinState.lockedUntil,
            unlockedUntil = pinState.unlockedUntil,
        )
    }

    fun timerConfig(): RestrictedTimerConfig {
        val base = RestrictedTimerConfig()
        val override = kidsQuotaSeconds()
        return if (override > 0L) {
            base.copy(weekdayQuotaSeconds = override, weekendQuotaSeconds = override)
        } else {
            base
        }
    }

    fun newTimer(initial: RestrictedTimerSnapshot = RestrictedTimerSnapshot.EMPTY): RestrictedTimer =
        RestrictedTimer(clock = clock, config = timerConfig(), timeZone = timeZone, initial = initial)

    /**
     * Hashes a plaintext value written by the debug `set_pref pin` command and removes it.
     * Returns true when a valid PIN was imported.
     */
    fun importLegacyPlainPin(): Boolean {
        val legacy = prefs.all[KEY_LEGACY_PIN]?.toString() ?: return false
        val imported = parentPin.set(legacy)
        prefs.edit().remove(KEY_LEGACY_PIN).apply()
        return imported
    }

    private fun applyResumedMode(resumedWire: String?): RestrictedMode? {
        prefs.edit().putString(KEY_PAUSED, false.toString()).apply()
        return resumedWire
            ?.let(RestrictedMode::fromWire)
            ?.takeIf { it != RestrictedMode.OFF }
    }

    internal class SharedPreferencesPinStore(
        private val prefs: SharedPreferences,
    ) : PinStore {
        override fun get(key: String): String? = prefs.all[key]?.toString()

        override fun put(key: String, value: String?) {
            prefs.edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "launcher_settings"

        const val KEY_MODE = "restricted_mode"
        const val KEY_ALLOWED = "restricted_allowed"
        const val KEY_KIDS_QUOTA = "kids_quota_s"
        const val KEY_REMAINING = "restricted_remaining_s"
        const val KEY_PAUSED = "restricted_paused"
        const val KEY_LEGACY_PIN = "pin"
    }
}
