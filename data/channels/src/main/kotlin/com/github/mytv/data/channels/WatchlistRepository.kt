package com.github.mytv.data.channels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.watchlistStore: DataStore<Preferences> by preferencesDataStore("launcher_watchlist")

class WatchlistRepository(private val context: Context) {
    private val key = stringPreferencesKey("items")
    private val prefs = context.watchlistStore

    val entries: Flow<List<WatchNextEntry>> = prefs.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { decode(it[key]) }

    suspend fun toggle(entry: WatchNextEntry) {
        val current = try {
            decode(prefs.data.first()[key])
        } catch (_: IOException) {
            emptyList()
        }
        val without = current.filterNot { it.watchlistKey == entry.watchlistKey }
        val next = if (without.size == current.size) current + entry else without
        prefs.edit { it[key] = encode(next) }
    }

    private fun decode(raw: String?): List<WatchNextEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pkg = item.optString("package")
                    if (pkg.isEmpty()) continue
                    add(
                        WatchNextEntry(
                            packageName = pkg,
                            internalProviderId = item.optString("internalId"),
                            id = item.optLong("id"),
                            title = item.optString("title"),
                            posterArtUri = item.optString("poster").takeIf(String::isNotEmpty),
                            intentUri = item.optString("intent").takeIf(String::isNotEmpty),
                            durationMillis = item.optLong("duration"),
                            playbackPositionMillis = item.optLong("position"),
                            type = item.optInt("type"),
                            seasonNumber = item.optInt("season", -1).takeIf { it >= 0 },
                            episodeNumber = item.optInt("episode", -1).takeIf { it >= 0 },
                            lastEngagementTimeMillis = item.optLong("engagement"),
                            description = item.optString("description").takeIf(String::isNotEmpty),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encode(items: List<WatchNextEntry>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("package", item.packageName)
                    .put("internalId", item.internalProviderId)
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("poster", item.posterArtUri.orEmpty())
                    .put("intent", item.intentUri.orEmpty())
                    .put("duration", item.durationMillis)
                    .put("position", item.playbackPositionMillis)
                    .put("type", item.type)
                    .put("season", item.seasonNumber ?: -1)
                    .put("episode", item.episodeNumber ?: -1)
                    .put("engagement", item.lastEngagementTimeMillis)
                    .put("description", item.description.orEmpty()),
            )
        }
        return array.toString()
    }
}
