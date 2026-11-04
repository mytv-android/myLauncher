package com.github.mytv.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLookTest {
    @Test
    fun clearPartlyOvercastAndFog() {
        assertEquals(Sky.Clear, lookOf(0)?.sky)
        assertEquals(R.string.wx_clear, lookOf(0)?.label)
        assertEquals(Sky.Clear, lookOf(1)?.sky)
        assertEquals(Sky.Partly, lookOf(2)?.sky)
        assertEquals(R.string.wx_cloudy, lookOf(2)?.label)
        assertEquals(Sky.Overcast, lookOf(3)?.sky)
        assertEquals(R.string.wx_overcast, lookOf(3)?.label)
        assertEquals(Sky.Fog, lookOf(45)?.sky)
        assertEquals(Sky.Fog, lookOf(48)?.sky)
        assertEquals(R.string.wx_fog, lookOf(48)?.label)
    }

    @Test
    fun rainCodes() {
        for (code in intArrayOf(51, 53, 55)) {
            assertEquals(Sky.Rain, lookOf(code)?.sky)
            assertEquals(R.string.wx_drizzle, lookOf(code)?.label)
        }
        for (code in intArrayOf(56, 57, 66, 67)) {
            assertEquals(Sky.Rain, lookOf(code)?.sky)
            assertEquals(R.string.wx_freezing_rain, lookOf(code)?.label)
        }
        assertEquals(R.string.wx_rain_light, lookOf(61)?.label)
        assertEquals(R.string.wx_rain_moderate, lookOf(63)?.label)
        assertEquals(R.string.wx_rain_heavy, lookOf(65)?.label)
        for (code in intArrayOf(80, 81, 82)) {
            assertEquals(Sky.Rain, lookOf(code)?.sky)
            assertEquals(R.string.wx_showers, lookOf(code)?.label)
        }
    }

    @Test
    fun snowCodes() {
        for (code in intArrayOf(71, 77)) {
            assertEquals(Sky.Snow, lookOf(code)?.sky)
            assertEquals(R.string.wx_snow_light, lookOf(code)?.label)
        }
        assertEquals(R.string.wx_snow_moderate, lookOf(73)?.label)
        assertEquals(R.string.wx_snow_heavy, lookOf(75)?.label)
        for (code in intArrayOf(85, 86)) {
            assertEquals(Sky.Snow, lookOf(code)?.sky)
            assertEquals(R.string.wx_snow_showers, lookOf(code)?.label)
        }
    }

    @Test
    fun thunderHailAndUnknownCodes() {
        assertEquals(Sky.Thunder, lookOf(95)?.sky)
        assertEquals(R.string.wx_thunder, lookOf(95)?.label)
        for (code in intArrayOf(96, 99)) {
            assertEquals(Sky.Thunder, lookOf(code)?.sky)
            assertEquals(R.string.wx_hail, lookOf(code)?.label)
        }
        assertNull(lookOf(-1))
        assertNull(lookOf(4))
        assertNull(lookOf(100))
    }

    @Test
    fun nightVariantOnlyForClearAndPartly() {
        assertTrue(isNightVariant(0, 0))
        assertTrue(isNightVariant(1, 0))
        assertTrue(isNightVariant(2, 0))
        assertFalse(isNightVariant(3, 0))
        assertFalse(isNightVariant(61, 0))
        assertFalse(isNightVariant(0, 1))
        assertFalse(isNightVariant(2, 1))
    }
}
