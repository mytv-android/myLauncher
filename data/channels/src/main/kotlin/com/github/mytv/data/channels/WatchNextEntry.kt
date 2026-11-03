package com.github.mytv.data.channels

data class WatchNextEntry(
    val packageName: String,
    val internalProviderId: String,
    val id: Long,
    val title: String,
    val posterArtUri: String?,
    val intentUri: String?,
    val durationMillis: Long,
    val playbackPositionMillis: Long,
    val type: Int,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEngagementTimeMillis: Long,
    val description: String?,
) {
    val key: String
        get() = "wn:$packageName:${internalProviderId.ifEmpty { "#$id" }}"

    val watchlistKey: String
        get() = "wl:$packageName:${internalProviderId.ifEmpty { "#$id" }}"

    val remainingMinutes: Int
        get() {
            val remaining = (durationMillis - playbackPositionMillis).coerceAtLeast(0L)
            return Math.round(remaining / 60_000.0).toInt()
        }
}

fun parseDisplayNumber(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    raw.trim().toIntOrNull()?.let { return it }
    return raw.filter { it.isDigit() }.toIntOrNull()
}
