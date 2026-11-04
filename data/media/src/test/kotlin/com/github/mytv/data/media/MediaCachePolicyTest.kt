package com.github.mytv.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCachePolicyTest {
    @Test
    fun cacheCapClampsSmallFreeSpaceTo64MB() {
        assertEquals(64L * 1024 * 1024, MediaCachePolicy.cacheCapBytes(0))
        assertEquals(64L * 1024 * 1024, MediaCachePolicy.cacheCapBytes(100L * 1024 * 1024))
    }

    @Test
    fun cacheCapUsesQuarterOfFreeSpaceInTheMiddleOfTheRange() {
        val free = 2L * 1024 * 1024 * 1024
        assertEquals(free / 4, MediaCachePolicy.cacheCapBytes(free))
    }

    @Test
    fun cacheCapClampsLargeDisksTo3GB() {
        val free = 100L * 1024 * 1024 * 1024
        assertEquals(3L * 1024 * 1024 * 1024, MediaCachePolicy.cacheCapBytes(free))
    }

    @Test
    fun prefetchOnlyOnUnmeteredNetworkInLastEightSeconds() {
        assertTrue(MediaCachePolicy.shouldPrefetch(networkMetered = false, millisUntilScreensaver = 8_000))
        assertTrue(MediaCachePolicy.shouldPrefetch(networkMetered = false, millisUntilScreensaver = 0))
        assertFalse(MediaCachePolicy.shouldPrefetch(networkMetered = false, millisUntilScreensaver = 8_001))
        assertFalse(MediaCachePolicy.shouldPrefetch(networkMetered = true, millisUntilScreensaver = 1_000))
    }

    @Test
    fun connectAndReadTimeoutsAreFiveAndEightSeconds() {
        assertEquals(5_000, MediaCachePolicy.CONNECT_TIMEOUT_MS)
        assertEquals(8_000, MediaCachePolicy.READ_TIMEOUT_MS)
    }

    @Test
    fun eightSecondsWithoutAFrameSkipsTheClip() {
        assertFalse(MediaCachePolicy.shouldSkipClip(7_999))
        assertTrue(MediaCachePolicy.shouldSkipClip(8_000))
    }

    @Test
    fun threeConsecutiveFailuresFallBackToPhotosOrWallpaper() {
        assertEquals(AmbientFailureAction.NEXT_CLIP, MediaCachePolicy.actionAfterFailure(1))
        assertEquals(AmbientFailureAction.NEXT_CLIP, MediaCachePolicy.actionAfterFailure(2))
        assertEquals(
            AmbientFailureAction.PHOTOS_OR_WALLPAPER,
            MediaCachePolicy.actionAfterFailure(3),
        )
    }

    @Test
    fun decoderErrors4001To4005DowngradeOneTierWithoutChangingTheSetting() {
        val setting = MediaQualitySetting(MediaQuality.DOLBY_VISION_4K)
        assertTrue(MediaCachePolicy.isDecoderError(4_001))
        assertTrue(MediaCachePolicy.isDecoderError(4_005))
        assertFalse(MediaCachePolicy.isDecoderError(4_000))
        assertFalse(MediaCachePolicy.isDecoderError(4_006))
        assertEquals(MediaQuality.HEVC_4K, MediaCachePolicy.downgraded(MediaQuality.DOLBY_VISION_4K))
        assertEquals(MediaQuality.H264_1080P, MediaCachePolicy.downgraded(MediaQuality.HEVC_4K))
        assertNull(MediaCachePolicy.downgraded(MediaQuality.H264_1080P))

        assertEquals(MediaQuality.HEVC_4K, setting.inProcessTier(4_001))
        assertEquals(MediaQuality.HEVC_4K, setting.inProcessTier(4_002))
        assertEquals(MediaQuality.DOLBY_VISION_4K, setting.inProcessTier(null))
        assertEquals(MediaQuality.DOLBY_VISION_4K, setting.selected)
    }
}
