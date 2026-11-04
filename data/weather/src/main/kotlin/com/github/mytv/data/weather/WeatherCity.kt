package com.github.mytv.data.weather

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Built-in city table. Names stay in a data resource rather than Kotlin string
 * literals; the weather settings accept the same Chinese city names.
 */
object WeatherCity {
    private const val RESOURCE = "com/github/mytv/data/weather/cities.tsv"

    private val table: Map<String, GeoPoint> by lazy { load() }

    fun coordinates(city: String?): GeoPoint? {
        val name = city?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return table[name]
    }

    fun cities(): Map<String, GeoPoint> = table

    private fun load(): Map<String, GeoPoint> {
        val stream = WeatherCity::class.java.classLoader?.getResourceAsStream(RESOURCE)
            ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    val latitude = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val longitude = parts[2].trim().toDoubleOrNull() ?: return@mapNotNull null
                    parts[0].trim() to GeoPoint(latitude, longitude)
                }
                .toMap()
        }
    }
}
