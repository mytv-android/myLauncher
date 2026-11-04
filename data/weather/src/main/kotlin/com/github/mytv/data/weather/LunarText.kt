package com.github.mytv.data.weather

import android.content.Context

/**
 * Builds the display form of a lunar date, for example month nine + day
 * twelve. The resource arrays store complete dates because the interface copy
 * whitelist only accepts full lunar dates; the fixed two-character day suffix
 * of a month entry and the fixed two-character day of a day entry are removed
 * here. The resource-independent form is covered by LunarCalendarTest.
 */
object LunarText {
    private const val NORMAL_MONTH_COUNT = 12
    private const val DAY_TEXT_LENGTH = 2

    fun format(context: Context, lunar: LunarDate): String = format(
        monthEntries = context.resources.getStringArray(R.array.lunar_months),
        dayEntries = context.resources.getStringArray(R.array.lunar_days),
        lunar = lunar,
    )

    fun format(monthEntries: Array<String>, dayEntries: Array<String>, lunar: LunarDate): String =
        format(monthEntries, dayEntries, lunar.month, lunar.day, lunar.isLeap)

    fun format(
        monthEntries: Array<String>,
        dayEntries: Array<String>,
        month: Int,
        day: Int,
        isLeap: Boolean,
    ): String {
        require(month in 1..12) { "month out of range" }
        require(day in 1..30) { "day out of range" }
        require(monthEntries.size >= NORMAL_MONTH_COUNT * 2) { "month table too short" }
        require(dayEntries.size >= 30) { "day table too short" }

        val monthIndex = (if (isLeap) NORMAL_MONTH_COUNT else 0) + month - 1
        val monthText = monthEntries[monthIndex].dropLast(DAY_TEXT_LENGTH)
        val dayText = dayEntries[day - 1].takeLast(DAY_TEXT_LENGTH)
        return monthText + dayText
    }
}
