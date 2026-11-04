package com.github.mytv.data.weather

/**
 * 24-hour mode unless Settings.System.TIME_12_24 is exactly "12".
 *
 * Deliberately does not use DateFormat.is24HourFormat(): when the setting is
 * empty that call falls back to the locale default, and zh_CN would report a
 * 12-hour clock.
 */
fun is24h(time1224: String?): Boolean = time1224 != "12"
