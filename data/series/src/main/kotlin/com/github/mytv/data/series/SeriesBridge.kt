package com.github.mytv.data.series

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process

class SeriesBridge(private val context: Context) {
    companion object {
        const val ACTION = "com.github.mytv.action.LAUNCHER_BRIDGE"
        const val PATH_CHANNELS = "/channels"
        const val PATH_PREVIEW = "/preview_programs"
        const val PATH_WATCH_NEXT = "/watch_next_programs"
    }

    fun discover(): List<SeriesProvider> {
        val intent = Intent(ACTION)
        val found = try {
            context.packageManager.queryIntentContentProviders(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val out = LinkedHashMap<String, SeriesProvider>()
        for (info in found) {
            val providerInfo = info.providerInfo
            val pkg = providerInfo.packageName
            if (!SeriesTrust.isTrusted(context, pkg)) continue
            val authority = providerInfo.authority ?: continue
            out[pkg] = SeriesProvider(pkg, authority, providerInfo.name)
        }
        return out.values.toList()
    }

    fun hello(provider: SeriesProvider): Bundle? = call(provider, "hello", null, null)

    fun init(provider: SeriesProvider): Bundle? = call(provider, "init", null, null)

    fun approve(provider: SeriesProvider, key: String): Bundle? =
        call(provider, "approve", key, Bundle().apply { putBoolean("approved", true) })

    fun hide(provider: SeriesProvider, key: String): Bundle? = call(provider, "hide", key, null)

    private fun call(provider: SeriesProvider, method: String, arg: String?, extras: Bundle?): Bundle? =
        try {
            context.contentResolver.call(
                Uri.parse("content://${provider.authority}"),
                method,
                arg,
                extras,
            )
        } catch (_: Exception) {
            null
        }

    fun channels(provider: SeriesProvider): List<BridgeChannel> =
        query(provider, PATH_CHANNELS) { c ->
            BridgeChannel(
                packageName = provider.packageName,
                id = c.long("_id"),
                key = c.string("internal_provider_id").orEmpty(),
                title = c.string("display_name").orEmpty(),
                logoUri = null,
                appLinkIntentUri = c.string("app_link_intent_uri"),
                browsable = c.int("browsable") != 0,
            )
        }

    fun previewPrograms(provider: SeriesProvider): List<BridgeProgram> =
        query(provider, PATH_PREVIEW) { c ->
            BridgeProgram(
                packageName = provider.packageName,
                channelKey = c.string("channel_id").orEmpty(),
                key = c.string("internal_provider_id").orEmpty(),
                title = c.string("title").orEmpty(),
                description = c.string("short_description"),
                posterArtUri = c.string("poster_art_uri"),
                aspectRatio = c.int("poster_art_aspect_ratio"),
                type = c.int("type"),
                intentUri = c.string("intent_uri"),
                weight = c.int("weight"),
                live = c.int("live") != 0,
                durationMillis = c.long("duration_millis"),
                playbackPositionMillis = c.long("last_playback_position_millis"),
                seasonNumber = c.string("season_display_number")?.toIntOrNull(),
                episodeNumber = c.string("episode_display_number")?.toIntOrNull(),
                lastEngagementTimeMillis = c.long("last_engagement_time_utc_millis"),
                genre = c.string("canonical_genre"),
                releaseDate = c.string("release_date"),
            )
        }

    fun watchNext(provider: SeriesProvider): List<BridgeWatchNext> =
        query(provider, PATH_WATCH_NEXT) { c ->
            BridgeWatchNext(
                packageName = provider.packageName,
                key = c.string("internal_provider_id").orEmpty(),
                title = c.string("title").orEmpty(),
                description = c.string("short_description"),
                posterArtUri = c.string("poster_art_uri"),
                intentUri = c.string("intent_uri"),
                type = c.int("type"),
                durationMillis = c.long("duration_millis"),
                playbackPositionMillis = c.long("last_playback_position_millis"),
                seasonNumber = c.string("season_display_number")?.toIntOrNull(),
                episodeNumber = c.string("episode_display_number")?.toIntOrNull(),
                lastEngagementTimeMillis = c.long("last_engagement_time_utc_millis"),
            )
        }

    private fun <T> query(provider: SeriesProvider, path: String, map: (Cursor) -> T): List<T> {
        val uri = Uri.parse("content://${provider.authority}$path")
        val cursor = try {
            context.contentResolver.query(uri, null, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<T>()
        cursor.use {
            while (it.moveToNext()) {
                try {
                    out += map(it)
                } catch (_: Exception) {
                }
            }
        }
        return out
    }
}

private fun Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.long(column: String): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0L else getLong(index)
}

private fun Cursor.int(column: String): Int {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0 else getInt(index)
}

fun SeriesProvider.callerUid(): Int = Process.myUid()
