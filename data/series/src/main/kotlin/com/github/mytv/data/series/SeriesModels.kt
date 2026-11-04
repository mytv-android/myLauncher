package com.github.mytv.data.series

data class SeriesProvider(
    val packageName: String,
    val authority: String,
    val className: String,
)

data class BridgeChannel(
    val packageName: String,
    val id: Long,
    val key: String,
    val title: String,
    val logoUri: String?,
    val appLinkIntentUri: String?,
    val browsable: Boolean,
)

data class BridgeProgram(
    val packageName: String,
    val channelKey: String,
    val key: String,
    val title: String,
    val description: String?,
    val posterArtUri: String?,
    val aspectRatio: Int,
    val type: Int,
    val intentUri: String?,
    val weight: Int,
    val live: Boolean,
    val durationMillis: Long,
    val playbackPositionMillis: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEngagementTimeMillis: Long,
    val genre: String?,
    val releaseDate: String?,
)

data class BridgeWatchNext(
    val packageName: String,
    val key: String,
    val title: String,
    val description: String?,
    val posterArtUri: String?,
    val intentUri: String?,
    val type: Int,
    val durationMillis: Long,
    val playbackPositionMillis: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEngagementTimeMillis: Long,
)
