package com.github.mytv.data.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ClockFormatTest {
    @Test
    fun exactlyTwelveUsesTwelveHourClock() {
        assertFalse(is24h("12"))
    }

    @Test
    fun missingSettingUsesTwentyFourHourClock() {
        assertTrue(is24h(null))
    }

    @Test
    fun emptySettingUsesTwentyFourHourClock() {
        assertTrue(is24h(""))
        assertTrue(is24h(" "))
    }

    @Test
    fun explicitTwentyFourUsesTwentyFourHourClock() {
        assertTrue(is24h("24"))
    }

    @Test
    fun otherValuesUseTwentyFourHourClock() {
        assertTrue(is24h("12.0"))
        assertTrue(is24h("twelve"))
    }

    @Test
    fun chineseLocaleDoesNotChangeTheRule() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertTrue(is24h(null))
            assertTrue(is24h("24"))
            assertFalse(is24h("12"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
