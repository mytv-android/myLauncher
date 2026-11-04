package com.github.mytv.data.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BingDailyImageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeFeed(
        private val images: List<BingImage> = emptyList(),
        private val failure: Boolean = false,
    ) : BingFeed {
        var calls = 0
        override suspend fun fetch(): List<BingImage> {
            calls++
            if (failure) error("offline")
            return images
        }
    }

    private class FakeDownloader : BingDownloader {
        val urls = ArrayList<String>()

        override suspend fun download(url: String, target: File): Boolean {
            urls += url
            target.parentFile?.mkdirs()
            target.writeText("image")
            return true
        }
    }

    private fun image(date: String, wp: Boolean = true) = BingImage(
        date = date,
        url = "https://example.invalid/$date.jpg",
        urlbase = "https://example.invalid/base_$date",
        copyright = "Example $date",
        wp = wp,
    )

    private fun dayMillis(day: Int, hour: Int = 12): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.clear()
        calendar.set(2026, java.util.Calendar.JANUARY, day, hour, 0, 0)
        return calendar.timeInMillis
    }

    @Test
    fun propertiesStoreRoundTrips() {
        val directory = temporaryFolder.newFolder("store")
        val store = PropertiesBingStateStore(File(directory, BingDailyImage.STATE_FILE))
        store.write(
            BingState(
                lastRequestDay = "20260101",
                images = listOf(BingStoredImage("f.jpg", "20260101", "c", "u")),
            ),
        )
        assertTrue(File(directory, BingDailyImage.STATE_FILE).exists())
        assertEquals("20260101", store.read().lastRequestDay)
        assertEquals(1, store.read().images.size)
    }

    @Test
    fun requestsTheFeedAtMostOncePerDay() = runBlocking {
        val feed = FakeFeed(listOf(image("20260101")))
        val directory = temporaryFolder.newFolder("bing-1")
        val subject = BingDailyImage(
            directory = directory,
            feed = feed,
            downloader = FakeDownloader(),
            clock = { dayMillis(1) },
        )
        val first = subject.refresh(1080)
        val second = subject.refresh(1080)
        assertEquals(1, feed.calls)
        assertTrue(first is BingRefresh.Ready)
        assertTrue(second is BingRefresh.SkippedForToday)
    }

    @Test
    fun onlyWpTrueImagesAreUsed() = runBlocking {
        val downloader = FakeDownloader()
        val subject = BingDailyImage(
            directory = temporaryFolder.newFolder("bing-2"),
            feed = FakeFeed(listOf(image("20260101", wp = true), image("20260102", wp = false))),
            downloader = downloader,
            clock = { dayMillis(1) },
        )
        val result = subject.refresh(1080)
        assertTrue(result is BingRefresh.Ready)
        assertEquals(1, downloader.urls.size)
        assertTrue(downloader.urls.single().contains("20260101"))
        assertFalse(downloader.urls.any { it.contains("20260102") })
    }

    @Test
    fun uhdUrlIsUsedOnlyAbove1080p() {
        val image = image("20260101")
        assertEquals(image.url, BingDailyImage.bestUrl(image, 1080))
        assertEquals(image.urlbase + "_UHD.jpg", BingDailyImage.bestUrl(image, 1081))
        assertEquals(image.urlbase + "_UHD.jpg", BingDailyImage.bestUrl(image, 2160))
    }

    @Test
    fun onlyTheNewestEightImagesAreKept() = runBlocking {
        val directory = temporaryFolder.newFolder("bing-3")
        val store = PropertiesBingStateStore(File(directory, BingDailyImage.STATE_FILE))
        val existing = (1..8).map { day ->
            val date = "202601%02d".format(day)
            val file = File(directory, "bing_${date}_0.jpg").apply { writeText("old") }
            BingStoredImage(file.name, date, "old", "https://example.invalid/$date.jpg")
        }
        store.write(BingState(lastRequestDay = "20260108", images = existing))

        val subject = BingDailyImage(
            directory = directory,
            feed = FakeFeed(listOf(image("20260109"))),
            downloader = FakeDownloader(),
            clock = { dayMillis(9) },
        )
        val result = subject.refresh(1080)
        assertTrue(result is BingRefresh.Ready)
        assertEquals(8, subject.images().size)
        assertTrue(subject.images().none { it.date == "20260101" })
        assertFalse(File(directory, "bing_20260101_0.jpg").exists())
        assertTrue(subject.images().any { it.date == "20260109" })
    }

    @Test
    fun disablingTheSourceDeletesFilesAndFallsBackToBuiltinWallpaper() = runBlocking {
        val directory = temporaryFolder.newFolder("bing-4")
        val stored = File(directory, "bing_20260101_0.jpg").apply { writeText("image") }
        val other = File(directory, "keep.txt").apply { writeText("keep") }
        val store = PropertiesBingStateStore(File(directory, BingDailyImage.STATE_FILE))
        store.write(
            BingState(
                lastRequestDay = "20260101",
                images = listOf(BingStoredImage(stored.name, "20260101", "c", "u")),
            ),
        )
        val subject = BingDailyImage(directory, FakeFeed(), FakeDownloader(), clock = { dayMillis(1) })
        val result = subject.disable()
        assertEquals(BingRefresh.Disabled, result)
        assertFalse(stored.exists())
        assertTrue(other.exists())
        assertTrue(subject.images().isEmpty())
        assertNull(subject.currentImageFile())
    }

    @Test
    fun failureFallsBackAndStillCountsAsTheDailyRequest() = runBlocking {
        val feed = FakeFeed(failure = true)
        val subject = BingDailyImage(
            directory = temporaryFolder.newFolder("bing-5"),
            feed = feed,
            downloader = FakeDownloader(),
            clock = { dayMillis(1) },
        )
        assertTrue(subject.refresh(1080) is BingRefresh.Fallback)
        assertTrue(subject.refresh(1080) is BingRefresh.Fallback)
        assertEquals(1, feed.calls)
    }
}
