package com.github.mytv.data.weather

import java.util.Locale
import kotlin.math.roundToInt

data class WeatherState(
    val visible: Boolean,
    val tempC: Int?,
    val code: Int?,
    val isDay: Boolean,
    val city: String?,
    val updatedAtMillis: Long,
)

/** Only coordinates with two decimals and the language leave the device. */
data class WeatherRequest(
    val latitude: String,
    val longitude: String,
    val language: String,
)

sealed interface ForecastResult {
    data class Success(
        val temperatureC: Double,
        val weatherCode: Int,
        val isDay: Boolean,
    ) : ForecastResult

    data object Failure : ForecastResult
}

data class IpLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String?,
)

interface WeatherFetcher {
    suspend fun fetchForecast(request: WeatherRequest): ForecastResult
    suspend fun locateByIp(): IpLocation?
}

data class WeatherReading(
    val tempC: Int,
    val code: Int,
    val isDay: Boolean,
    val city: String?,
    val updatedAtMillis: Long,
)

/** Everything the repository persists between refreshes. */
data class WeatherPolicyState(
    val lastForecastAtMillis: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastFailureAtMillis: Long = 0L,
    val lastIpLookupAtMillis: Long = 0L,
    val resolvedCity: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val reading: WeatherReading? = null,
)

/**
 * Rate and fallback rules from design section 12:
 * - at most one successful forecast request per hour;
 * - a cached reading stays visible for three hours;
 * - failures retry after 1, 5, then 15 minutes;
 * - IP geolocation runs at most once a day and never when a city is set;
 * - coordinates are sent with two decimals and language "zh".
 *
 * The clock and the network fetcher are injected so the rules are testable
 * without Android or a network.
 */
class WeatherPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetcher: WeatherFetcher,
) {
    suspend fun refresh(state: WeatherPolicyState, configuredCity: String?): WeatherPolicyState {
        val now = clock()
        val city = configuredCity?.trim()?.takeIf { it.isNotEmpty() }
        var next = state
        val latitude: Double?
        val longitude: Double?
        val cityLabel: String?

        if (city != null) {
            val point = WeatherCity.coordinates(city)
            latitude = point?.latitude
            longitude = point?.longitude
            cityLabel = city
        } else {
            if (isIpLookupDue(next, city, now)) {
                val location = fetcher.locateByIp()
                next = next.copy(lastIpLookupAtMillis = now)
                if (location != null) {
                    next = next.copy(
                        resolvedCity = location.city,
                        latitude = location.latitude,
                        longitude = location.longitude,
                    )
                }
            }
            latitude = next.latitude
            longitude = next.longitude
            cityLabel = next.resolvedCity
        }

        if (latitude == null || longitude == null) return next
        val cityChanged = cityLabel != null && state.reading != null && state.reading.city != cityLabel
        if (!cityChanged && !isForecastDue(next, now)) return next

        val request = WeatherRequest(
            latitude = formatCoordinate(latitude),
            longitude = formatCoordinate(longitude),
            language = REQUEST_LANGUAGE,
        )
        return when (val result = fetcher.fetchForecast(request)) {
            is ForecastResult.Success -> next.copy(
                lastForecastAtMillis = now,
                consecutiveFailures = 0,
                reading = WeatherReading(
                    tempC = result.temperatureC.roundToInt(),
                    code = result.weatherCode,
                    isDay = result.isDay,
                    city = cityLabel,
                    updatedAtMillis = now,
                ),
            )

            ForecastResult.Failure -> next.copy(
                consecutiveFailures = next.consecutiveFailures + 1,
                lastFailureAtMillis = now,
            )
        }
    }

    fun weatherState(state: WeatherPolicyState, now: Long = clock()): WeatherState {
        val reading = state.reading
            ?: return WeatherState(
                visible = false,
                tempC = null,
                code = null,
                isDay = true,
                city = null,
                updatedAtMillis = 0L,
            )
        val visible = now - reading.updatedAtMillis < VISIBLE_FOR_MS
        return WeatherState(
            visible = visible,
            tempC = if (visible) reading.tempC else null,
            code = if (visible) reading.code else null,
            isDay = reading.isDay,
            city = if (visible) reading.city else null,
            updatedAtMillis = reading.updatedAtMillis,
        )
    }

    fun isForecastDue(state: WeatherPolicyState, now: Long = clock()): Boolean {
        if (state.consecutiveFailures > 0) {
            return now - state.lastFailureAtMillis >= failureBackoffMillis(state.consecutiveFailures)
        }
        return state.lastForecastAtMillis == 0L ||
            now - state.lastForecastAtMillis >= REQUEST_INTERVAL_MS
    }

    fun isIpLookupDue(
        state: WeatherPolicyState,
        configuredCity: String?,
        now: Long = clock(),
    ): Boolean {
        if (!configuredCity.isNullOrBlank()) return false
        if (state.lastIpLookupAtMillis == 0L) return true
        return now - state.lastIpLookupAtMillis >= IP_LOOKUP_INTERVAL_MS
    }

    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    companion object {
        const val REQUEST_INTERVAL_MS = 3_600_000L
        const val VISIBLE_FOR_MS = 10_800_000L
        const val IP_LOOKUP_INTERVAL_MS = 86_400_000L
        const val REQUEST_LANGUAGE = "zh"

        fun failureBackoffMillis(failures: Int): Long = when {
            failures <= 1 -> 60_000L
            failures == 2 -> 300_000L
            else -> 900_000L
        }
    }
}
