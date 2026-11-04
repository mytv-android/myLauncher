package com.github.mytv.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.Properties

/** One image from the Bing HPImageArchive feed. */
data class BingImage(
    val date: String,
    val url: String,
    val urlbase: String,
    val copyright: String,
    val wp: Boolean,
)

/** One downloaded image; [fileName] is relative to the wallpaper directory. */
data class BingStoredImage(
    val fileName: String,
    val date: String,
    val copyright: String,
    val remoteUrl: String,
)

data class BingState(
    val lastRequestDay: String = "",
    val images: List<BingStoredImage> = emptyList(),
)

sealed interface BingRefresh {
    data class Ready(val images: List<BingStoredImage>) : BingRefresh

    /** A request was already made today; the existing set is unchanged. */
    data class SkippedForToday(val images: List<BingStoredImage>) : BingRefresh

    /** Failure or no usable image: the caller shows the built-in wallpaper. */
    data class Fallback(val images: List<BingStoredImage> = emptyList()) : BingRefresh

    /** The source was disabled and its files were deleted. */
    data object Disabled : BingRefresh
}

interface BingFeed {
    suspend fun fetch(): List<BingImage>
}

interface BingDownloader {
    suspend fun download(url: String, target: File): Boolean
}

interface BingStateStore {
    fun read(): BingState
    fun write(state: BingState)
}

/**
 * Bing daily wallpaper policy from §13:
 *
 *  - at most one feed request per local day;
 *  - only images with wp=true;
 *  - screens above 1080p use urlbase + "_UHD.jpg", otherwise url;
 *  - keep the newest 8 local images;
 *  - disabling the source deletes its files and returns to the built-in wallpaper.
 */
class BingDailyImage(
    private val directory: File,
    private val feed: BingFeed,
    private val downloader: BingDownloader,
    private val clock: () -> Long = System::currentTimeMillis,
    store: BingStateStore? = null,
) {
    private val store: BingStateStore =
        store ?: PropertiesBingStateStore(File(directory, STATE_FILE))

    suspend fun refresh(screenHeightPx: Int): BingRefresh {
        val today = dayKey(clock())
        var state = store.read()
        if (state.lastRequestDay == today) {
            return if (state.images.isEmpty()) {
                BingRefresh.Fallback()
            } else {
                BingRefresh.SkippedForToday(state.images)
            }
        }
        state = state.copy(lastRequestDay = today)
        val fetched = runCatching { feed.fetch() }.getOrDefault(emptyList())
        val usable = fetched.filter { it.wp && it.url.isNotBlank() }
        if (usable.isEmpty()) {
            store.write(state)
            return BingRefresh.Fallback(state.images)
        }

        val downloaded = ArrayList(state.images)
        usable.take(MAX_IMAGES).forEachIndexed { index, image ->
            val remoteUrl = bestUrl(image, screenHeightPx)
            val fileName = "bing_${image.date.ifBlank { today }}_$index.jpg"
            val target = File(directory, fileName)
            directory.mkdirs()
            if (runCatching { downloader.download(remoteUrl, target) }.getOrDefault(false)) {
                downloaded.add(BingStoredImage(fileName, image.date, image.copyright, remoteUrl))
            }
        }

        val kept = downloaded
            .filter { File(directory, it.fileName).exists() }
            .sortedByDescending { it.date }
            .distinctBy { it.date }
            .take(MAX_IMAGES)
        state = state.copy(images = kept)
        store.write(state)
        deleteFilesNotIn(kept)
        return if (kept.isEmpty()) BingRefresh.Fallback() else BingRefresh.Ready(kept)
    }

    fun images(): List<BingStoredImage> = store.read().images

    /** Current image file, or null when the built-in wallpaper must be shown. */
    fun currentImageFile(): File? =
        images().firstOrNull()?.let { File(directory, it.fileName).takeIf(File::exists) }

    /** Turning the source off deletes every downloaded image and state. */
    fun disable(): BingRefresh {
        directory.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".jpg") && it.isFile }
            ?.forEach { it.delete() }
        store.write(BingState())
        return BingRefresh.Disabled
    }

    private fun deleteFilesNotIn(kept: List<BingStoredImage>) {
        val names = kept.mapTo(HashSet()) { it.fileName }
        directory.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".jpg") && it.isFile && it.name !in names }
            ?.forEach { it.delete() }
    }

    companion object {
        const val MAX_IMAGES = 8
        const val UHD_THRESHOLD_PX = 1080
        const val STATE_FILE = "bing_state.properties"
        private const val FILE_PREFIX = "bing_"

        /** Exactly the URL rule from §13. */
        fun bestUrl(image: BingImage, screenHeightPx: Int): String =
            if (screenHeightPx > UHD_THRESHOLD_PX && image.urlbase.isNotBlank()) {
                image.urlbase + "_UHD.jpg"
            } else {
                image.url
            }

        fun dayKey(millis: Long): String {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = millis
            return String.format(
                Locale.US,
                "%04d%02d%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}

/** Properties-backed store; the file lives beside the downloaded images. */
class PropertiesBingStateStore(private val file: File) : BingStateStore {
    @Synchronized
    override fun read(): BingState {
        if (!file.exists()) return BingState()
        val properties = Properties()
        runCatching { file.inputStream().use { properties.load(it) } }
        val count = properties.getProperty("count")?.toIntOrNull() ?: 0
        val images = buildList {
            for (index in 0 until count) {
                val fileName = properties.getProperty("image.$index.file") ?: continue
                add(
                    BingStoredImage(
                        fileName = fileName,
                        date = properties.getProperty("image.$index.date").orEmpty(),
                        copyright = properties.getProperty("image.$index.copyright").orEmpty(),
                        remoteUrl = properties.getProperty("image.$index.url").orEmpty(),
                    ),
                )
            }
        }
        return BingState(properties.getProperty("lastDay").orEmpty(), images)
    }

    @Synchronized
    override fun write(state: BingState) {
        val properties = Properties()
        properties.setProperty("lastDay", state.lastRequestDay)
        properties.setProperty("count", state.images.size.toString())
        state.images.forEachIndexed { index, image ->
            properties.setProperty("image.$index.file", image.fileName)
            properties.setProperty("image.$index.date", image.date)
            properties.setProperty("image.$index.copyright", image.copyright)
            properties.setProperty("image.$index.url", image.remoteUrl)
        }
        file.parentFile?.mkdirs()
        runCatching { file.outputStream().use { properties.store(it, null) } }
    }
}

/** HTTP implementation of the HPImageArchive feed. */
class HttpBingFeed(
    private val endpoint: String = DEFAULT_ENDPOINT,
) : BingFeed {
    override suspend fun fetch(): List<BingImage> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseFeed(body)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT =
            "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN"

        fun parseFeed(body: String): List<BingImage> {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val images = root.optJSONArray("images") ?: return emptyList()
            return buildList(images.length()) {
                for (index in 0 until images.length()) {
                    val item = images.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (url.isBlank()) continue
                    add(
                        BingImage(
                            date = item.optString("startdate"),
                            url = url,
                            urlbase = item.optString("urlbase"),
                            copyright = item.optString("copyright"),
                            wp = item.optBoolean("wp", false),
                        ),
                    )
                }
            }
        }
    }
}

/** Writes the requested URL to [target]; partial files are removed on failure. */
class HttpBingDownloader : BingDownloader {
    override suspend fun download(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            if (connection.responseCode !in 200..299) return@withContext false
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            target.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }
}
