package com.github.mytv.data.channels

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WatchNextRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<WatchNextEntry>>(emptyList())
    val items: StateFlow<List<WatchNextEntry>> = _items.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                context.contentResolver.registerContentObserver(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    true,
                    observer,
                )
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    fun refresh() {
        if (Build.VERSION.SDK_INT < 26) {
            _items.value = emptyList()
            _loaded.value = true
            return
        }
        scope.launch {
            _items.value = query()
            _loaded.value = true
        }
    }

    private fun query(): List<WatchNextEntry> {
        val projection = arrayOf(
            BaseColumns._ID,
            TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_TYPE,
            TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
        )
        val cursor: Cursor = try {
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                projection,
                null,
                null,
                null,
            ) ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<WatchNextEntry>()
        cursor.use {
            while (it.moveToNext()) {
                val pkg = it.string(1)
                if (pkg.isNullOrEmpty()) continue
                out += WatchNextEntry(
                    packageName = pkg,
                    internalProviderId = it.string(2).orEmpty(),
                    id = it.long(0),
                    title = it.string(3).orEmpty(),
                    posterArtUri = it.string(4)?.takeIf(String::isNotBlank),
                    intentUri = it.string(5)?.takeIf(String::isNotBlank),
                    durationMillis = it.long(6),
                    playbackPositionMillis = it.long(7),
                    type = it.int(8),
                    seasonNumber = parseDisplayNumber(it.string(9)),
                    episodeNumber = parseDisplayNumber(it.string(10)),
                    lastEngagementTimeMillis = it.long(11),
                    description = it.string(12),
                )
            }
        }
        return out.sortedByDescending { it.lastEngagementTimeMillis }
    }
}

private fun Cursor.string(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun Cursor.long(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)

private fun Cursor.int(index: Int): Int =
    if (index < 0 || isNull(index)) 0 else getInt(index)
