package com.github.mytv.data.weather

import androidx.annotation.StringRes

enum class Sky { Clear, Partly, Overcast, Fog, Rain, Snow, Thunder }

data class WeatherLook(val sky: Sky, @StringRes val label: Int)

/** WMO weather code mapping from supplement A3. Unknown codes have no icon. */
fun lookOf(wmo: Int): WeatherLook? = when (wmo) {
    0, 1 -> WeatherLook(Sky.Clear, R.string.wx_clear)
    2 -> WeatherLook(Sky.Partly, R.string.wx_cloudy)
    3 -> WeatherLook(Sky.Overcast, R.string.wx_overcast)
    45, 48 -> WeatherLook(Sky.Fog, R.string.wx_fog)
    51, 53, 55 -> WeatherLook(Sky.Rain, R.string.wx_drizzle)
    56, 57, 66, 67 -> WeatherLook(Sky.Rain, R.string.wx_freezing_rain)
    61 -> WeatherLook(Sky.Rain, R.string.wx_rain_light)
    63 -> WeatherLook(Sky.Rain, R.string.wx_rain_moderate)
    65 -> WeatherLook(Sky.Rain, R.string.wx_rain_heavy)
    80, 81, 82 -> WeatherLook(Sky.Rain, R.string.wx_showers)
    71, 77 -> WeatherLook(Sky.Snow, R.string.wx_snow_light)
    73 -> WeatherLook(Sky.Snow, R.string.wx_snow_moderate)
    75 -> WeatherLook(Sky.Snow, R.string.wx_snow_heavy)
    85, 86 -> WeatherLook(Sky.Snow, R.string.wx_snow_showers)
    95 -> WeatherLook(Sky.Thunder, R.string.wx_thunder)
    96, 99 -> WeatherLook(Sky.Thunder, R.string.wx_hail)
    else -> null
}

/** Clear and partly cloudy get a moon icon when Open-Meteo reports night. */
fun isNightVariant(wmo: Int, isDay: Int): Boolean {
    if (isDay != 0) return false
    return lookOf(wmo)?.sky.let { it == Sky.Clear || it == Sky.Partly }
}
