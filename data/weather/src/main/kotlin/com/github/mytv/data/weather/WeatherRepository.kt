package com.github.mytv.data.weather

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-Meteo client with the policies from WeatherPolicy and a
 * SharedPreferences cache. No location permission is requested: when the user
 * has not chosen a city the public ipwho.is endpoint is consulted at most once
 * a day.
 */
class WeatherRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val fetcher = HttpWeatherFetcher(prefs)
    private val policy = WeatherPolicy(clock = clock, fetcher = fetcher)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(policy.weatherState(loadState()))
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    fun snapshot(): WeatherState = _state.value

    /** Applies the hourly, backoff and daily rules; a no-op when nothing is due. */
    suspend fun refresh(): WeatherState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
            var loadState = loadState()
            if (prefs.getString(KEY_LAST_BASE_URL, null) != baseUrl) {
                prefs.edit().putString(KEY_LAST_BASE_URL, baseUrl).apply()
                loadState = loadState.copy(
                    lastForecastAtMillis = 0L,
                    consecutiveFailures = 0,
                    lastFailureAtMillis = 0L,
                )
            }
            val updated = policy.refresh(loadState, prefs.getString(KEY_CITY, null))
            saveState(updated)
            val weather = policy.weatherState(updated)
            _state.value = weather
            weather
        }
    }

    private fun loadState(): WeatherPolicyState {
        val hasReading = prefs.getLong(KEY_UPDATED_AT, 0L) > 0L
        val reading = if (hasReading) {
            WeatherReading(
                tempC = prefs.getInt(KEY_TEMP_C, 0),
                code = prefs.getInt(KEY_CODE, 0),
                isDay = prefs.getBoolean(KEY_IS_DAY, true),
                city = prefs.getString(KEY_READING_CITY, null),
                updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L),
            )
        } else {
            null
        }
        return WeatherPolicyState(
            lastForecastAtMillis = prefs.getLong(KEY_LAST_FORECAST_AT, 0L),
            consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
            lastFailureAtMillis = prefs.getLong(KEY_LAST_FAILURE_AT, 0L),
            lastIpLookupAtMillis = prefs.getLong(KEY_LAST_IP_AT, 0L),
            resolvedCity = prefs.getString(KEY_RESOLVED_CITY, null),
            latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull(),
            longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull(),
            reading = reading,
        )
    }

    private fun saveState(state: WeatherPolicyState) {
        val editor = prefs.edit()
            .putLong(KEY_LAST_FORECAST_AT, state.lastForecastAtMillis)
            .putInt(KEY_FAILURES, state.consecutiveFailures)
            .putLong(KEY_LAST_FAILURE_AT, state.lastFailureAtMillis)
            .putLong(KEY_LAST_IP_AT, state.lastIpLookupAtMillis)
            .putString(KEY_RESOLVED_CITY, state.resolvedCity)
            .putString(KEY_LATITUDE, state.latitude?.toString())
            .putString(KEY_LONGITUDE, state.longitude?.toString())
        val reading = state.reading
        if (reading == null) {
            editor.remove(KEY_TEMP_C)
                .remove(KEY_CODE)
                .remove(KEY_IS_DAY)
                .remove(KEY_READING_CITY)
                .remove(KEY_UPDATED_AT)
        } else {
            editor.putInt(KEY_TEMP_C, reading.tempC)
                .putInt(KEY_CODE, reading.code)
                .putBoolean(KEY_IS_DAY, reading.isDay)
                .putString(KEY_READING_CITY, reading.city)
                .putLong(KEY_UPDATED_AT, reading.updatedAtMillis)
        }
        // commit，而不是 apply：请求时间必须落盘，进程被强制结束重启后仍要遵守「每小时最多一次」
        editor.commit()
    }

    private class HttpWeatherFetcher(private val prefs: SharedPreferences) : WeatherFetcher {
        override suspend fun fetchForecast(request: WeatherRequest): ForecastResult =
            withContext(Dispatchers.IO) {
                val base = prefs.getString(KEY_BASE_URL, null)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_BASE_URL
                val url = base.trimEnd('/') + "/v1/forecast" +
                    "?latitude=${request.latitude}&longitude=${request.longitude}" +
                    "&current_weather=true&current=temperature_2m,weather_code,is_day" +
                    "&timezone=auto&language=${request.language}"
                val body = get(url) ?: return@withContext ForecastResult.Failure
                parseForecast(body) ?: ForecastResult.Failure
            }

        override suspend fun locateByIp(): IpLocation? = withContext(Dispatchers.IO) {
            val body = get(IP_URL) ?: return@withContext null
            parseIpLocation(body)
        }

        private fun get(url: String): String? {
            var connection: HttpURLConnection? = null
            return try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    null
                } else {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }

        private fun parseForecast(body: String): ForecastResult? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val currentWeather = json.optJSONObject("current_weather")
            val current = json.optJSONObject("current")
            val temperature = number(currentWeather, "temperature")
                ?: number(current, "temperature_2m")
                ?: return null
            val code = integer(currentWeather, "weathercode")
                ?: integer(current, "weather_code")
                ?: return null
            val isDay = when {
                currentWeather?.has("is_day") == true -> currentWeather.optInt("is_day", 1) != 0
                current?.has("is_day") == true -> current.optInt("is_day", 1) != 0
                else -> true
            }
            return ForecastResult.Success(temperature, code, isDay)
        }

        private fun parseIpLocation(body: String): IpLocation? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (!json.optBoolean("success", true)) return null
            val latitude = number(json, "latitude") ?: return null
            val longitude = number(json, "longitude") ?: return null
            val city = json.optString("city").trim().takeIf { it.isNotEmpty() }
            return IpLocation(latitude, longitude, city)
        }

        private fun number(obj: JSONObject?, key: String): Double? =
            when (val value = obj?.opt(key)) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }

        private fun integer(obj: JSONObject?, key: String): Int? =
            when (val value = obj?.opt(key)) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
    }

    private companion object {
        const val PREFS_NAME = "launcher_settings"
        const val KEY_CITY = "weather_city"
        const val KEY_BASE_URL = "weather_base_url"
        const val KEY_LAST_BASE_URL = "weather_last_base_url"
        const val DEFAULT_BASE_URL = "https://api.open-meteo.com"
        const val IP_URL = "https://ipwho.is/"
        const val TIMEOUT_MS = 10_000

        const val KEY_LAST_FORECAST_AT = "weather_last_forecast_at"
        const val KEY_FAILURES = "weather_failure_count"
        const val KEY_LAST_FAILURE_AT = "weather_last_failure_at"
        const val KEY_LAST_IP_AT = "weather_last_ip_at"
        const val KEY_RESOLVED_CITY = "weather_resolved_city"
        const val KEY_LATITUDE = "weather_latitude"
        const val KEY_LONGITUDE = "weather_longitude"
        const val KEY_TEMP_C = "weather_temp_c"
        const val KEY_CODE = "weather_code"
        const val KEY_IS_DAY = "weather_is_day"
        const val KEY_READING_CITY = "weather_reading_city"
        const val KEY_UPDATED_AT = "weather_updated_at"
    }
}
