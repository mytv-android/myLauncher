package com.github.mytv.data.restricted

import java.util.Calendar
import java.util.TimeZone

/** Daily quota and rest policy for kids mode. */
data class RestrictedTimerConfig(
    val weekdayQuotaSeconds: Long = 2 * 60 * 60L,
    val weekendQuotaSeconds: Long = 3 * 60 * 60L,
    val allowedStartMinute: Int = 8 * 60,
    val allowedEndMinute: Int = 20 * 60 + 30,
    val continuousWatchLimitSeconds: Long = 45 * 60L,
    val restSeconds: Long = 15 * 60L,
) {
    init {
        require(weekdayQuotaSeconds >= 0L) { "weekdayQuotaSeconds must be >= 0" }
        require(weekendQuotaSeconds >= 0L) { "weekendQuotaSeconds must be >= 0" }
        require(continuousWatchLimitSeconds >= 0L) { "continuousWatchLimitSeconds must be >= 0" }
        require(restSeconds > 0L) { "restSeconds must be > 0" }
        require(allowedStartMinute in 0..1440) { "allowedStartMinute out of range" }
        require(allowedEndMinute in 0..1440) { "allowedEndMinute out of range" }
    }
}

enum class RestrictedLockReason {
    NONE,
    QUOTA_EXHAUSTED,
    OUTSIDE_ALLOWED_HOURS,
    RESTING,
}

/** One-shot reminder emitted when the remaining quota crosses a threshold. */
data class UsageNotice(val minutesLeft: Int)

data class RestrictedTimerSnapshot(
    val dayKey: Long = 0L,
    val usedSeconds: Long = 0L,
    val continuousWatchSeconds: Long = 0L,
    val lastTickMillis: Long = 0L,
    val lastWatching: Boolean = false,
    val lastWatchingMillis: Long = 0L,
    val restUntilMillis: Long = 0L,
    val bonusSeconds: Long = 0L,
    val unlimitedToday: Boolean = false,
    val noticeFiveSent: Boolean = false,
    val noticeOneSent: Boolean = false,
) {
    companion object {
        val EMPTY = RestrictedTimerSnapshot()
    }
}

data class RestrictedTimerEvaluation(
    val watching: Boolean,
    val quotaSeconds: Long,
    val usedSeconds: Long,
    val remainingSeconds: Long,
    val locked: Boolean,
    val lockReason: RestrictedLockReason,
    val restRemainingSeconds: Long,
    val notices: List<UsageNotice>,
    val unlimitedToday: Boolean,
)

/**
 * Accumulates screen time while a foreground app is allowed.
 *
 * Accounting rules (design section 14):
 * - the caller ticks about every 10 seconds; an interval of at most 20 seconds counts,
 *   a longer gap is treated as standby and is not counted;
 * - weekday and weekend quotas are separate;
 * - outside the allowed hours the timer reports OUTSIDE_ALLOWED_HOURS and does not count;
 * - after [RestrictedTimerConfig.continuousWatchLimitSeconds] of continuous watching the
 *   timer reports RESTING for [RestrictedTimerConfig.restSeconds]; staying away from an
 *   app for the rest duration also clears the continuous counter;
 * - a snapshot keeps the last tick timestamp so a new process can reconstruct the elapsed
 *   time from recorded timestamps.
 */
class RestrictedTimer(
    private val clock: Clock = Clock.system(),
    private var config: RestrictedTimerConfig = RestrictedTimerConfig(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
    initial: RestrictedTimerSnapshot = RestrictedTimerSnapshot.EMPTY,
) {
    private var state: RestrictedTimerSnapshot = initial
    private var firstEvaluation: Boolean = true

    fun updateConfig(config: RestrictedTimerConfig) {
        this.config = config
    }

    fun snapshot(): RestrictedTimerSnapshot = state

    fun restore(snapshot: RestrictedTimerSnapshot) {
        state = snapshot
        firstEvaluation = true
    }

    fun addBonusSeconds(seconds: Long) {
        require(seconds >= 0L) { "bonus seconds must be >= 0" }
        state = state.copy(bonusSeconds = state.bonusSeconds + seconds)
    }

    fun grantUnlimitedToday() {
        state = state.copy(unlimitedToday = true)
    }

    fun evaluate(
        foregroundAllowedApp: Boolean,
        screenOn: Boolean = true,
    ): RestrictedTimerEvaluation =
        evaluate(clock.nowMillis(), foregroundAllowedApp, screenOn)

    fun evaluate(
        nowMillis: Long,
        foregroundAllowedApp: Boolean,
        screenOn: Boolean = true,
    ): RestrictedTimerEvaluation {
        val calendar = calendar(nowMillis)
        val today = dayKey(calendar)
        if (state.dayKey != today) {
            state = RestrictedTimerSnapshot(dayKey = today, lastTickMillis = nowMillis)
        }

        val quotaSeconds = quotaFor(calendar)
        val unlimited = state.unlimitedToday
        val totalQuota = if (unlimited) Long.MAX_VALUE else quotaSeconds + state.bonusSeconds
        val remainingBefore = remaining(totalQuota, state.usedSeconds, unlimited)
        val allowedNow = isWithinAllowedHours(calendar)

        var restExpiredNow = false
        if (state.restUntilMillis != 0L && nowMillis >= state.restUntilMillis) {
            state = state.copy(
                restUntilMillis = 0L,
                continuousWatchSeconds = 0L,
                lastTickMillis = nowMillis,
            )
            restExpiredNow = true
        }
        val restingAtStart = state.restUntilMillis > nowMillis

        val inputWatching = screenOn && foregroundAllowedApp
        val replayFromSnapshot = firstEvaluation && state.lastWatching
        val elapsedMillis = if (state.lastTickMillis <= 0L) 0L else (nowMillis - state.lastTickMillis).coerceAtLeast(0L)
        val elapsedSeconds = elapsedMillis / 1000L
        val intervalCounts = elapsedMillis in 1..MAX_COUNTED_GAP_MILLIS
        val canAccumulate = !restingAtStart && !restExpiredNow && allowedNow &&
            (unlimited || state.usedSeconds < totalQuota)

        if (canAccumulate && intervalCounts && (inputWatching || replayFromSnapshot)) {
            val used = if (unlimited) {
                state.usedSeconds
            } else {
                (state.usedSeconds + elapsedSeconds).coerceAtMost(totalQuota)
            }
            val continuous = state.continuousWatchSeconds + elapsedSeconds
            state = state.copy(
                usedSeconds = used,
                continuousWatchSeconds = continuous,
                lastWatchingMillis = nowMillis,
            )
            if (config.continuousWatchLimitSeconds > 0L && continuous >= config.continuousWatchLimitSeconds) {
                state = state.copy(
                    restUntilMillis = nowMillis + config.restSeconds * 1000L,
                    continuousWatchSeconds = 0L,
                )
            }
        } else if (elapsedMillis > MAX_COUNTED_GAP_MILLIS) {
            state = state.copy(continuousWatchSeconds = 0L)
        }

        if (!allowedNow) {
            state = state.copy(continuousWatchSeconds = 0L)
        } else if (!inputWatching && state.lastWatchingMillis > 0L &&
            nowMillis - state.lastWatchingMillis >= config.restSeconds * 1000L
        ) {
            state = state.copy(continuousWatchSeconds = 0L)
        }

        val restingAfter = state.restUntilMillis > nowMillis
        val remainingAfter = remaining(totalQuota, state.usedSeconds, unlimited)
        val notices = ArrayList<UsageNotice>(2)
        if (!unlimited && remainingBefore > FIVE_MINUTE_NOTICE_SECONDS &&
            remainingAfter <= FIVE_MINUTE_NOTICE_SECONDS && !state.noticeFiveSent
        ) {
            notices += UsageNotice(5)
            state = state.copy(noticeFiveSent = true)
        }
        if (!unlimited && remainingBefore > ONE_MINUTE_NOTICE_SECONDS &&
            remainingAfter <= ONE_MINUTE_NOTICE_SECONDS && !state.noticeOneSent
        ) {
            notices += UsageNotice(1)
            state = state.copy(noticeOneSent = true)
        }

        val lockReason = when {
            restingAfter -> RestrictedLockReason.RESTING
            !allowedNow -> RestrictedLockReason.OUTSIDE_ALLOWED_HOURS
            !unlimited && remainingAfter <= 0L -> RestrictedLockReason.QUOTA_EXHAUSTED
            else -> RestrictedLockReason.NONE
        }
        val restRemainingSeconds = if (restingAfter) {
            (state.restUntilMillis - nowMillis + 999L) / 1000L
        } else {
            0L
        }
        val countingNow = inputWatching && allowedNow && !restingAfter &&
            (unlimited || remainingAfter > 0L)
        state = state.copy(lastTickMillis = nowMillis, lastWatching = countingNow)
        firstEvaluation = false

        return RestrictedTimerEvaluation(
            watching = inputWatching,
            quotaSeconds = quotaSeconds,
            usedSeconds = state.usedSeconds,
            remainingSeconds = remainingAfter,
            locked = lockReason != RestrictedLockReason.NONE,
            lockReason = lockReason,
            restRemainingSeconds = restRemainingSeconds,
            notices = notices,
            unlimitedToday = unlimited,
        )
    }

    private fun calendar(nowMillis: Long): Calendar =
        Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }

    private fun dayKey(calendar: Calendar): Long =
        calendar.get(Calendar.YEAR).toLong() * 1000L + calendar.get(Calendar.DAY_OF_YEAR).toLong()

    private fun quotaFor(calendar: Calendar): Long =
        when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY, Calendar.SUNDAY -> config.weekendQuotaSeconds
            else -> config.weekdayQuotaSeconds
        }

    private fun isWithinAllowedHours(calendar: Calendar): Boolean {
        val start = config.allowedStartMinute
        val end = config.allowedEndMinute
        if (start == end) return true
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (start < end) {
            minute >= start && minute < end
        } else {
            minute >= start || minute < end
        }
    }

    private fun remaining(totalQuota: Long, usedSeconds: Long, unlimited: Boolean): Long =
        if (unlimited) Long.MAX_VALUE else (totalQuota - usedSeconds).coerceAtLeast(0L)

    private companion object {
        const val MAX_COUNTED_GAP_MILLIS = 20_000L
        const val FIVE_MINUTE_NOTICE_SECONDS = 5 * 60L
        const val ONE_MINUTE_NOTICE_SECONDS = 60L
    }
}
