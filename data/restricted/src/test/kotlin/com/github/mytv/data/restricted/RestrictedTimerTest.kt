package com.github.mytv.data.restricted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RestrictedTimerTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun config(
        weekday: Long = 3600L,
        weekend: Long = 3600L,
        startMinute: Int = 0,
        endMinute: Int = 24 * 60,
        continuous: Long = 3600L,
        rest: Long = 60L,
    ): RestrictedTimerConfig = RestrictedTimerConfig(
        weekdayQuotaSeconds = weekday,
        weekendQuotaSeconds = weekend,
        allowedStartMinute = startMinute,
        allowedEndMinute = endMinute,
        continuousWatchLimitSeconds = continuous,
        restSeconds = rest,
    )

    private fun timer(config: RestrictedTimerConfig, start: Long): RestrictedTimer =
        RestrictedTimer(clock = Clock { start }, config = config, timeZone = utc)

    @Test
    fun accumulatesEveryTenSeconds() {
        val t0 = at(2026, 1, 5, 10, 0)
        val timer = timer(config(weekday = 600L), t0)
        timer.evaluate(t0, foregroundAllowedApp = true)

        var now = t0
        var evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        assertEquals(0L, evaluation.usedSeconds)

        repeat(3) {
            now += 10_000L
            evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        }
        assertEquals(30L, evaluation.usedSeconds)
        assertEquals(570L, evaluation.remainingSeconds)
    }

    @Test
    fun gapOverTwentySecondsIsNotCounted() {
        val t0 = at(2026, 1, 5, 10, 0)
        val timer = timer(config(weekday = 600L), t0)
        timer.evaluate(t0, foregroundAllowedApp = true)

        var evaluation = timer.evaluate(t0 + 25_000L, foregroundAllowedApp = true)
        assertEquals(0L, evaluation.usedSeconds)

        evaluation = timer.evaluate(t0 + 35_000L, foregroundAllowedApp = true)
        assertEquals(10L, evaluation.usedSeconds)

        val exact = timer(config(weekday = 600L), t0)
        exact.evaluate(t0, foregroundAllowedApp = true)
        evaluation = exact.evaluate(t0 + 20_000L, foregroundAllowedApp = true)
        assertEquals(20L, evaluation.usedSeconds)
    }

    @Test
    fun weekdayAndWeekendUseDifferentQuotas() {
        val monday = at(2026, 1, 5, 10, 0)
        val saturday = at(2026, 1, 10, 10, 0)
        val timer = timer(config(weekday = 600L, weekend = 1200L), monday)

        var evaluation = timer.evaluate(monday, foregroundAllowedApp = true)
        assertEquals(600L, evaluation.quotaSeconds)

        var now = monday
        repeat(60) {
            now += 10_000L
            evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        }
        assertEquals(600L, evaluation.usedSeconds)
        assertTrue(evaluation.locked)
        assertEquals(RestrictedLockReason.QUOTA_EXHAUSTED, evaluation.lockReason)

        val weekendTimer = timer(config(weekday = 600L, weekend = 1200L), saturday)
        evaluation = weekendTimer.evaluate(saturday, foregroundAllowedApp = true)
        assertEquals(1200L, evaluation.quotaSeconds)
        now = saturday
        repeat(60) {
            now += 10_000L
            evaluation = weekendTimer.evaluate(now, foregroundAllowedApp = true)
        }
        assertEquals(600L, evaluation.usedSeconds)
        assertFalse(evaluation.locked)
    }

    @Test
    fun outsideAllowedHoursDoesNotCount() {
        val evening = at(2026, 1, 6, 21, 0)
        val eveningTimer = timer(
            config(weekday = 600L, startMinute = 8 * 60, endMinute = 20 * 60 + 30),
            evening,
        )
        var evaluation = eveningTimer.evaluate(evening, foregroundAllowedApp = true)
        assertTrue(evaluation.locked)
        assertEquals(RestrictedLockReason.OUTSIDE_ALLOWED_HOURS, evaluation.lockReason)
        assertEquals(0L, evaluation.usedSeconds)

        val morning = at(2026, 1, 6, 10, 0)
        val morningTimer = timer(
            config(weekday = 600L, startMinute = 8 * 60, endMinute = 20 * 60 + 30),
            morning,
        )
        morningTimer.evaluate(morning, foregroundAllowedApp = true)
        evaluation = morningTimer.evaluate(morning + 10_000L, foregroundAllowedApp = true)
        assertFalse(evaluation.locked)
        assertEquals(10L, evaluation.usedSeconds)
    }

    @Test
    fun continuousWatchingTriggersRest() {
        val t0 = at(2026, 1, 5, 10, 0)
        val timer = timer(config(weekday = 1000L, continuous = 60L, rest = 30L), t0)
        var now = t0
        var evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        repeat(6) {
            now += 10_000L
            evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        }
        assertEquals(60L, evaluation.usedSeconds)
        assertTrue(evaluation.locked)
        assertEquals(RestrictedLockReason.RESTING, evaluation.lockReason)
        assertEquals(30L, evaluation.restRemainingSeconds)

        now += 10_000L
        evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        assertTrue(evaluation.locked)
        assertEquals(20L, evaluation.restRemainingSeconds)
        assertEquals(60L, evaluation.usedSeconds)

        now += 20_000L
        evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        assertFalse(evaluation.locked)
        assertEquals(60L, evaluation.usedSeconds)

        now += 10_000L
        evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        assertEquals(70L, evaluation.usedSeconds)
    }

    @Test
    fun processRestartReconstructsFromTimestamps() {
        val t0 = at(2026, 1, 5, 10, 0)
        val config = config(weekday = 600L)
        val first = timer(config, t0)
        first.evaluate(t0, foregroundAllowedApp = true)
        first.evaluate(t0 + 10_000L, foregroundAllowedApp = true)
        val snapshot = first.snapshot()
        assertEquals(10L, snapshot.usedSeconds)
        assertTrue(snapshot.lastWatching)

        val restarted = RestrictedTimer(
            clock = Clock { t0 + 25_000L },
            config = config,
            timeZone = utc,
            initial = snapshot,
        )
        var evaluation = restarted.evaluate(t0 + 25_000L, foregroundAllowedApp = true)
        assertEquals(25L, evaluation.usedSeconds)

        val late = RestrictedTimer(
            clock = Clock { t0 + 40_000L },
            config = config,
            timeZone = utc,
            initial = snapshot,
        )
        evaluation = late.evaluate(t0 + 40_000L, foregroundAllowedApp = true)
        assertEquals(10L, evaluation.usedSeconds)
        evaluation = late.evaluate(t0 + 50_000L, foregroundAllowedApp = true)
        assertEquals(20L, evaluation.usedSeconds)
    }

    @Test
    fun fiveAndOneMinuteNoticesAreSentOnce() {
        val t0 = at(2026, 1, 5, 10, 0)
        val timer = timer(config(weekday = 600L), t0)
        timer.evaluate(t0, foregroundAllowedApp = true)

        val notices = ArrayList<UsageNotice>()
        var now = t0
        var evaluation = timer.evaluate(now, foregroundAllowedApp = true)
        repeat(60) {
            now += 10_000L
            evaluation = timer.evaluate(now, foregroundAllowedApp = true)
            notices += evaluation.notices
        }
        assertEquals(listOf(UsageNotice(5), UsageNotice(1)), notices)

        val restarted = RestrictedTimer(
            clock = Clock { now + 10_000L },
            config = config(weekday = 600L),
            timeZone = utc,
            initial = timer.snapshot(),
        )
        evaluation = restarted.evaluate(now + 10_000L, foregroundAllowedApp = true)
        assertTrue(evaluation.notices.isEmpty())
        assertTrue(evaluation.locked)
    }
}
