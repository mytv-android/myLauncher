package com.github.mytv.data.channels

data class ChannelEntry(
    val packageName: String,
    val key: String,
    val title: String,
    val logoUri: String?,
    val appLinkIntentUri: String?,
    val approved: Boolean,
    val browsable: Boolean,
    val series: Boolean,
    val type: Int,
) {
    val rowId: String
        get() = "ch:$packageName:$key"
}

data class ChannelProgram(
    val packageName: String,
    val channelKey: String,
    val internalId: String,
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
    val id: Long,
) {
    val tag: String
        get() = "prog:$packageName:${internalId.ifEmpty { "#$id" }}"

    val rowId: String
        get() = "ch:$packageName:$channelKey"

    val isChannel: Boolean
        get() = type == 6 || live

    val widthRatio: Float
        get() = when {
            type == 6 && aspectRatio == 0 -> 1f
            aspectRatio == 0 -> 16f / 9f
            aspectRatio == 1 -> 3f / 2f
            aspectRatio == 2 -> 4f / 3f
            aspectRatio == 3 -> 1f
            aspectRatio == 4 -> 2f / 3f
            aspectRatio == 5 -> 1f / 1.441f
            else -> 1f
        }

    val remainingMinutes: Int
        get() {
            val remaining = (durationMillis - playbackPositionMillis).coerceAtLeast(0L)
            return Math.round(remaining / 60_000.0).toInt()
        }
}

data class ChannelApproval(
    val packageName: String,
    val channelKey: String,
    val state: String,
)
