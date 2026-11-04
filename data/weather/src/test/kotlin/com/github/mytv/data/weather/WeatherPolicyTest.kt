package com.github.mytv.data.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPolicyTest {
    @Test
    fun forecastRunsAtMostOncePerHour() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher()
        val policy = WeatherPolicy({ clock.now }, fetcher)

        var state = policy.refresh(WeatherPolicyState(), "北京")
        assertEquals(1, fetcher.requests.size)
        assertEquals(23, policy.weatherState(state).tempC)

        clock.now += WeatherPolicy.REQUEST_INTERVAL_MS - 1
        state = policy.refresh(state, "北京")
        assertEquals(1, fetcher.requests.size)

        clock.now += 1
        state = policy.refresh(state, "北京")
        assertEquals(2, fetcher.requests.size)
        assertEquals(23, policy.weatherState(state).tempC)
    }

    @Test
    fun cachedReadingDisappearsAfterThreeHours() {
        val clock = MutableClock(1_000_000L)
        val policy = WeatherPolicy({ clock.now }, FakeFetcher())
        val state = WeatherPolicyState(
            reading = WeatherReading(
                tempC = 23,
                code = 0,
                isDay = true,
                city = "北京",
                updatedAtMillis = clock.now,
            ),
        )

        clock.now += WeatherPolicy.VISIBLE_FOR_MS - 1
        assertTrue(policy.weatherState(state).visible)

        clock.now += 1
        val hidden = policy.weatherState(state)
        assertFalse(hidden.visible)
        assertNull(hidden.tempC)
        assertNull(hidden.code)
        assertNull(hidden.city)
    }

    @Test
    fun failuresBackOffOneFiveAndFifteenMinutes() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher().apply { forecastResult = ForecastResult.Failure }
        val policy = WeatherPolicy({ clock.now }, fetcher)

        var state = policy.refresh(WeatherPolicyState(), "北京")
        assertEquals(1, fetcher.requests.size)

        clock.now += 59_000L
        state = policy.refresh(state, "北京")
        assertEquals(1, fetcher.requests.size)

        clock.now += 1_000L
        state = policy.refresh(state, "北京")
        assertEquals(2, fetcher.requests.size)

        clock.now += 5 * 60_000L - 1
        state = policy.refresh(state, "北京")
        assertEquals(2, fetcher.requests.size)

        clock.now += 1
        state = policy.refresh(state, "北京")
        assertEquals(3, fetcher.requests.size)

        clock.now += 15 * 60_000L - 1
        state = policy.refresh(state, "北京")
        assertEquals(3, fetcher.requests.size)

        clock.now += 1
        policy.refresh(state, "北京")
        assertEquals(4, fetcher.requests.size)
    }

    @Test
    fun ipLookupRunsAtMostOncePerDay() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher()
        val policy = WeatherPolicy({ clock.now }, fetcher)

        var state = policy.refresh(WeatherPolicyState(), null)
        assertEquals(1, fetcher.ipCalls)
        assertEquals(1, fetcher.requests.size)

        clock.now += WeatherPolicy.REQUEST_INTERVAL_MS
        state = policy.refresh(state, null)
        assertEquals(1, fetcher.ipCalls)
        assertEquals(2, fetcher.requests.size)

        clock.now += WeatherPolicy.IP_LOOKUP_INTERVAL_MS - WeatherPolicy.REQUEST_INTERVAL_MS
        state = policy.refresh(state, null)
        assertEquals(2, fetcher.ipCalls)
        assertEquals(3, fetcher.requests.size)
        assertEquals("北京", policy.weatherState(state).city)
    }

    @Test
    fun configuredCityNeverUsesIpLocation() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher()
        val policy = WeatherPolicy({ clock.now }, fetcher)

        var state = WeatherPolicyState()
        repeat(3) {
            state = policy.refresh(state, "北京")
            clock.now += WeatherPolicy.IP_LOOKUP_INTERVAL_MS + 1
        }
        assertEquals(0, fetcher.ipCalls)
        assertEquals(3, fetcher.requests.size)
    }

    @Test
    fun unknownConfiguredCityDoesNotFallBackToIp() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher()
        val policy = WeatherPolicy({ clock.now }, fetcher)

        val state = policy.refresh(WeatherPolicyState(), "Atlantis")
        assertEquals(0, fetcher.ipCalls)
        assertEquals(0, fetcher.requests.size)
        assertFalse(policy.weatherState(state).visible)
    }

    @Test
    fun requestCarriesTwoDecimalCoordinatesAndChinese() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher()
        val policy = WeatherPolicy({ clock.now }, fetcher)

        policy.refresh(WeatherPolicyState(), "北京")
        val request = fetcher.requests.single()
        assertEquals("39.90", request.latitude)
        assertEquals("116.40", request.longitude)
        assertEquals("zh", request.language)
    }

    @Test
    fun failureKeepsThePreviousReadingVisible() = runBlocking {
        val clock = MutableClock(1_000_000L)
        val fetcher = FakeFetcher()
        val policy = WeatherPolicy({ clock.now }, fetcher)

        var state = policy.refresh(WeatherPolicyState(), "北京")
        assertEquals(23, policy.weatherState(state).tempC)

        fetcher.forecastResult = ForecastResult.Failure
        clock.now += WeatherPolicy.REQUEST_INTERVAL_MS
        state = policy.refresh(state, "北京")
        assertEquals(1, state.consecutiveFailures)
        assertEquals(23, policy.weatherState(state).tempC)
        assertTrue(policy.weatherState(state).visible)
    }

    private class MutableClock(var now: Long)

    private class FakeFetcher : WeatherFetcher {
        val requests = mutableListOf<WeatherRequest>()
        var forecastResult: ForecastResult = ForecastResult.Success(23.2, 0, true)
        var ipLocation: IpLocation? = IpLocation(39.9, 116.4, "北京")
        var ipCalls = 0

        override suspend fun fetchForecast(request: WeatherRequest): ForecastResult {
            requests += request
            return forecastResult
        }

        override suspend fun locateByIp(): IpLocation? {
            ipCalls += 1
            return ipLocation
        }
    }
}
