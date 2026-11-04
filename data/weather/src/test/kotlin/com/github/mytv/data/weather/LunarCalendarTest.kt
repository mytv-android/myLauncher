package com.github.mytv.data.weather

import com.ibm.icu.util.ChineseCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class LunarCalendarTest {
    private val zone = TimeZone.getTimeZone("GMT+8")

    @Test
    fun matchesIcuFrom1901Through2000() {
        assertRangeMatchesIcu(1901, 1, 1, 2001, 1, 1)
    }

    @Test
    fun matchesIcuFrom2001Through2100() {
        assertRangeMatchesIcu(2001, 1, 1, 2101, 1, 1)
    }

    @Test
    fun knownBoundariesAndLeapMonth() {
        assertEquals("11/11/0", LunarCalendar.lunarFor(1901, 1, 1)?.key)
        assertEquals("12/1/0", LunarCalendar.lunarFor(2100, 12, 31)?.key)
        assertEquals("11/1/1", LunarCalendar.lunarFor(2033, 12, 22)?.key)
        assertEquals("9/25/0", LunarCalendar.lunarFor(2026, 11, 3)?.key)
        assertNull(LunarCalendar.lunarFor(1900, 12, 31))
        assertNull(LunarCalendar.lunarFor(2101, 1, 1))
    }

    @Test
    fun formatsAsMonthAndDayText() {
        assertEquals(
            "九月十二",
            LunarText.format(monthEntries(), dayEntries(), LunarDate(2026, 9, 12, false)),
        )
        assertEquals(
            "九月廿五",
            LunarText.format(monthEntries(), dayEntries(), LunarCalendar.lunarFor(2026, 11, 3)!!),
        )
        assertEquals(
            "闰冬月初一",
            LunarText.format(monthEntries(), dayEntries(), LunarDate(2033, 11, 1, true)),
        )
    }

    private fun assertRangeMatchesIcu(
        startYear: Int,
        startMonth: Int,
        startDay: Int,
        endYear: Int,
        endMonth: Int,
        endDay: Int,
    ) {
        val gregorian = GregorianCalendar(zone).apply {
            clear()
            set(startYear, startMonth - 1, startDay, 12, 0, 0)
        }
        val icu = ChineseCalendar(com.ibm.icu.util.TimeZone.getTimeZone("GMT+8"))
        var checked = 0
        while (
            gregorian.get(Calendar.YEAR) < endYear ||
            gregorian.get(Calendar.MONTH) + 1 < endMonth ||
            gregorian.get(Calendar.DAY_OF_MONTH) < endDay
        ) {
            val year = gregorian.get(Calendar.YEAR)
            val month = gregorian.get(Calendar.MONTH) + 1
            val day = gregorian.get(Calendar.DAY_OF_MONTH)
            icu.timeInMillis = gregorian.timeInMillis
            val expectedMonth = icu.get(com.ibm.icu.util.Calendar.MONTH) + 1
            val expectedDay = icu.get(com.ibm.icu.util.Calendar.DAY_OF_MONTH)
            val expectedLeap = icu.get(ChineseCalendar.IS_LEAP_MONTH)
            val actual = LunarCalendar.lunarFor(year, month, day)
            assertEquals("no lunar date for $year-$month-$day", true, actual != null)
            assertEquals(
                "mismatch at $year-$month-$day",
                "$expectedMonth/$expectedDay/$expectedLeap",
                actual?.key,
            )
            checked += 1
            gregorian.add(Calendar.DAY_OF_MONTH, 1)
        }
        assertTrue("only $checked days checked", checked > 36_000)
    }

    private fun monthEntries(): Array<String> = arrayOf(
        "正月初一", "二月初一", "三月初一", "四月初一", "五月初一", "六月初一",
        "七月初一", "八月初一", "九月初一", "十月初一", "冬月初一", "腊月初一",
        "闰正月初一", "闰二月初一", "闰三月初一", "闰四月初一", "闰五月初一", "闰六月初一",
        "闰七月初一", "闰八月初一", "闰九月初一", "闰十月初一", "闰冬月初一", "闰腊月初一",
    )

    private fun dayEntries(): Array<String> = arrayOf(
        "正月初一", "正月初二", "正月初三", "正月初四", "正月初五", "正月初六",
        "正月初七", "正月初八", "正月初九", "正月初十", "正月十一", "正月十二",
        "正月十三", "正月十四", "正月十五", "正月十六", "正月十七", "正月十八",
        "正月十九", "正月二十", "正月廿一", "正月廿二", "正月廿三", "正月廿四",
        "正月廿五", "正月廿六", "正月廿七", "正月廿八", "正月廿九", "正月三十",
    )
}
