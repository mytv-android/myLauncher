package com.github.mytv.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCityTest {
    @Test
    fun builtInCitiesResolve() {
        val names = listOf(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉",
            "西安", "南京", "重庆", "天津", "苏州", "香港", "台北",
        )
        for (name in names) {
            assertNotNull("missing city $name", WeatherCity.coordinates(name))
        }
        assertTrue(WeatherCity.cities().size >= names.size)
        assertEquals(39.9, WeatherCity.coordinates("北京")!!.latitude, 0.0001)
        assertEquals(116.4, WeatherCity.coordinates("北京")!!.longitude, 0.0001)
        assertEquals(121.6, WeatherCity.coordinates("台北")!!.longitude, 0.0001)
    }

    @Test
    fun unknownOrBlankCityHasNoCoordinates() {
        assertNull(WeatherCity.coordinates("Atlantis"))
        assertNull(WeatherCity.coordinates(null))
        assertNull(WeatherCity.coordinates("   "))
        assertNull(WeatherCity.coordinates(""))
    }
}
