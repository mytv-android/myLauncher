package com.github.mytv.data.weather

import java.util.Calendar
import java.util.Date
import java.util.TimeZone

data class LunarDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val isLeap: Boolean,
) {
    /** Resource-independent representation used by the ICU comparison tests. */
    val key: String get() = "$month/$day/${if (isLeap) 1 else 0}"
}

/**
 * Self-contained Chinese lunar calendar for 1901-01-01 through 2100-12-31.
 *
 * The table uses the conventional packed representation for lunar years
 * 1900..2100: bits 0..3 hold the leap month (0 means none), bit 16 marks a
 * 30-day leap month, and bits 15..4 mark months 1..12 as 30-day months.
 * 1900-01-31 is lunar 1900-01-01 and serves as the epoch.
 */
object LunarCalendar {
    private const val FIRST_LUNAR_YEAR = 1900
    private const val BASE_EPOCH_DAY = -25537L // 1900-01-31
    private const val MIN_EPOCH_DAY = -25202L // 1901-01-01
    private const val MAX_EPOCH_DAY = 47846L   // 2100-12-31

    private val INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0,
        0x09ad0, 0x055d2, 0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540,
        0x0d6a0, 0x18da3, 0x095b0, 0x14977, 0x04970, 0x0a4b0, 0x1b0b6, 0x06a50,
        0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, 0x06566, 0x0d4a0,
        0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2,
        0x0a950, 0x0b557, 0x06ca0, 0x0b550, 0x15355, 0x04db0, 0x025b0, 0x18573,
        0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, 0x0aea6, 0x0ab50, 0x04b60, 0x0aae4,
        0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, 0x096d0, 0x04dd5,
        0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x1ad47,
        0x0ab60, 0x09570, 0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58,
        0x05ac0, 0x0ab60, 0x096e5, 0x092e0, 0x0c960, 0x0d954, 0x0d4a0, 0x0da50,
        0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, 0x0a950, 0x0b4a0,
        0x0bca4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x05270, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4f0, 0x05260,
        0x0ea65, 0x0d520, 0x0daa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0,
        0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, 0x0b5a0, 0x056d0, 0x055b2, 0x049b0,
        0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, 0x14b63, 0x09370,
        0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0,
        0x0a6d0, 0x055d4, 0x092d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50,
        0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, 0x0b273, 0x06930, 0x07337, 0x06aa0,
        0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, 0x0e968, 0x0d520,
        0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520,
    )

    fun lunarFor(year: Int, month: Int, day: Int): LunarDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val epoch = epochDay(year, month, day)
        if (epoch < MIN_EPOCH_DAY || epoch > MAX_EPOCH_DAY) return null
        return lunarForEpochDay(epoch)
    }

    fun lunarFor(date: Date, timeZone: TimeZone = TimeZone.getDefault()): LunarDate? {
        val calendar = Calendar.getInstance(timeZone).apply { time = date }
        return lunarFor(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun lunarForEpochDay(epoch: Long): LunarDate {
        var offset = (epoch - BASE_EPOCH_DAY).toInt()
        var year = FIRST_LUNAR_YEAR
        while (true) {
            val info = INFO[year - FIRST_LUNAR_YEAR]
            val daysInYear = yearDays(info)
            if (offset < daysInYear) break
            offset -= daysInYear
            year += 1
            check(year - FIRST_LUNAR_YEAR < INFO.size) { "lunar year outside table" }
        }

        val info = INFO[year - FIRST_LUNAR_YEAR]
        val leapMonth = info and 0xf
        val leapDays = leapMonthDays(info)
        var month = 1
        while (month <= 12) {
            val days = monthDays(info, month)
            if (offset < days) return LunarDate(year, month, offset + 1, false)
            offset -= days
            if (leapMonth == month) {
                if (offset < leapDays) return LunarDate(year, month, offset + 1, true)
                offset -= leapDays
            }
            month += 1
        }
        error("lunar day outside year")
    }

    private fun yearDays(info: Int): Int {
        var days = 0
        for (month in 1..12) days += monthDays(info, month)
        if ((info and 0xf) != 0) days += leapMonthDays(info)
        return days
    }

    private fun monthDays(info: Int, month: Int): Int =
        if ((info and (0x10000 shr month)) != 0) 30 else 29

    private fun leapMonthDays(info: Int): Int =
        if ((info and 0x10000) != 0) 30 else 29

    /** Days since 1970-01-01 for a proleptic Gregorian date. */
    private fun epochDay(year: Int, month: Int, day: Int): Long {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val era = Math.floorDiv(y, 400)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (m - 3) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * 146097L + dayOfEra - 719468L
    }
}
