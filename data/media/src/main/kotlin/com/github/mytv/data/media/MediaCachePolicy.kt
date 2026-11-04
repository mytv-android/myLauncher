package com.github.mytv.data.media

/**
 * Quality ladder used by the ambient player. The user setting is never
 * rewritten when the player downgrades in-process.
 */
enum class MediaQuality(val level: Int) {
    DOLBY_VISION_4K(3),
    HEVC_4K(2),
    H264_1080P(1),
    ;

    fun lower(): MediaQuality? = entries.firstOrNull { it.level == level - 1 }
}

/** What the player should do after the current clip failed. */
enum class AmbientFailureAction {
    NEXT_CLIP,
    PHOTOS_OR_WALLPAPER,
}

/**
 * The user's quality selection. [inProcessTier] may return a lower tier on a
 * decoder error, but this object is never written by the downgrade path.
 */
class MediaQualitySetting(selected: MediaQuality) {
    var selected: MediaQuality = selected
        private set

    fun inProcessTier(decoderErrorCode: Int?): MediaQuality? {
        if (decoderErrorCode == null || !MediaCachePolicy.isDecoderError(decoderErrorCode)) {
            return selected
        }
        return MediaCachePolicy.downgraded(selected)
    }
}

/**
 * Pure policy from §13 "画质、缓存与失败处理". Kept free of Android types so
 * T07 runs on the JVM.
 */
object MediaCachePolicy {
    const val MIN_CACHE_BYTES: Long = 64L * 1024 * 1024
    const val MAX_CACHE_BYTES: Long = 3L * 1024 * 1024 * 1024
    const val PREFETCH_BYTES: Long = 6L * 1024 * 1024
    const val PREFETCH_LEAD_MS: Long = 8_000
    const val CONNECT_TIMEOUT_MS: Int = 5_000
    const val READ_TIMEOUT_MS: Int = 8_000
    const val NO_FRAME_TIMEOUT_MS: Long = 8_000
    const val MAX_CONSECUTIVE_FAILURES: Int = 3
    const val FIRST_DECODER_ERROR_CODE: Int = 4001
    const val LAST_DECODER_ERROR_CODE: Int = 4005

    /** Cache cap: a quarter of free space, clamped to 64 MB–3 GB. */
    fun cacheCapBytes(freeBytes: Long): Long =
        (freeBytes / 4).coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)

    /**
     * Prefetch only in the last 8 seconds before the screensaver and only on
     * an unmetered network.
     */
    fun shouldPrefetch(networkMetered: Boolean, millisUntilScreensaver: Long): Boolean =
        !networkMetered && millisUntilScreensaver in 0..PREFETCH_LEAD_MS

    /** True when the first-frame watchdog should skip to the next clip. */
    fun shouldSkipClip(noFrameMillis: Long): Boolean = noFrameMillis >= NO_FRAME_TIMEOUT_MS

    /** The failure ladder: three consecutive bad clips fall back to photos. */
    fun actionAfterFailure(consecutiveFailures: Int): AmbientFailureAction =
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            AmbientFailureAction.PHOTOS_OR_WALLPAPER
        } else {
            AmbientFailureAction.NEXT_CLIP
        }

    /** Media3 decoder errors 4001–4005 trigger a one-step downgrade. */
    fun isDecoderError(code: Int): Boolean = code in FIRST_DECODER_ERROR_CODE..LAST_DECODER_ERROR_CODE

    /**
     * Returns the in-process quality after a decoder error, or null when the
     * ladder is already at 1080p H.264. This never mutates the user setting.
     */
    fun downgraded(current: MediaQuality): MediaQuality? = current.lower()

    /** Chooses the highest playable tier for the automatic setting. */
    fun resolveQuality(
        supportsDolbyVision: Boolean,
        supportsHevc: Boolean,
        supportsH264: Boolean = true,
    ): MediaQuality? = when {
        supportsDolbyVision -> MediaQuality.DOLBY_VISION_4K
        supportsHevc -> MediaQuality.HEVC_4K
        supportsH264 -> MediaQuality.H264_1080P
        else -> null
    }
}
