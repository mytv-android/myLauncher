package com.github.mytv.core.ambient

import java.io.File
import java.util.Properties

/**
 * Device-local verdict for frame capture. A verdict is remembered per
 * Build.FINGERPRINT so a firmware update probes the device again.
 */
data class FrameGrabVerdict(
    val pixelCopyDisabled: Boolean = false,
    val fileGrabDisabled: Boolean = false,
    val consecutiveBlackGrabs: Int = 0,
)

/** Conclusions preset by devices.json; any non-null field wins over measurement. */
data class FrameGrabPreset(
    val pixelCopyDisabled: Boolean? = null,
    val fileGrabDisabled: Boolean? = null,
)

interface FrameGrabVerdictStore {
    fun read(fingerprint: String): FrameGrabVerdict?
    fun write(fingerprint: String, verdict: FrameGrabVerdict)
}

class MemoryFrameGrabVerdictStore : FrameGrabVerdictStore {
    private val values = HashMap<String, FrameGrabVerdict>()

    @Synchronized
    override fun read(fingerprint: String): FrameGrabVerdict? = values[fingerprint]

    @Synchronized
    override fun write(fingerprint: String, verdict: FrameGrabVerdict) {
        values[fingerprint] = verdict
    }
}

/** Small file-backed store used when DataStore is not wired by the host yet. */
class FileFrameGrabVerdictStore(private val file: File) : FrameGrabVerdictStore {
    @Synchronized
    override fun read(fingerprint: String): FrameGrabVerdict? {
        if (!file.exists()) return null
        val properties = Properties()
        return runCatching {
            file.inputStream().use { properties.load(it) }
            val prefix = "$fingerprint."
            if (properties.getProperty("${prefix}present") != "true") return null
            FrameGrabVerdict(
                pixelCopyDisabled = properties.getProperty("${prefix}pixelCopyDisabled") == "true",
                fileGrabDisabled = properties.getProperty("${prefix}fileGrabDisabled") == "true",
                consecutiveBlackGrabs = properties.getProperty("${prefix}blackCount")?.toIntOrNull() ?: 0,
            )
        }.getOrNull()
    }

    @Synchronized
    override fun write(fingerprint: String, verdict: FrameGrabVerdict) {
        val properties = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { properties.load(it) } }
        }
        val prefix = "$fingerprint."
        properties.setProperty("${prefix}present", "true")
        properties.setProperty("${prefix}pixelCopyDisabled", verdict.pixelCopyDisabled.toString())
        properties.setProperty("${prefix}fileGrabDisabled", verdict.fileGrabDisabled.toString())
        properties.setProperty("${prefix}blackCount", verdict.consecutiveBlackGrabs.toString())
        file.parentFile?.mkdirs()
        runCatching { file.outputStream().use { properties.store(it, null) } }
    }
}

/** Process-wide state for the "no file grabs until the next boot" rule. */
class FileGrabBootState {
    @Volatile
    var fileGrabDisabled: Boolean = false
}

/**
 * Enforces the §13 frame-capture rules:
 *
 *  - a successful PixelCopy slower than 150 ms disables PixelCopy on this firmware;
 *  - a 16x9 grid whose maximum luminance is below 4% counts as all-black;
 *  - with a non-near-black clip key color, two consecutive black grabs disable PixelCopy;
 *  - a MediaMetadataRetriever timeout disables file grabs until the next boot;
 *  - a devices.json preset always wins over measured conclusions.
 */
class FrameGrabGuard(
    val fingerprint: String,
    private val store: FrameGrabVerdictStore = MemoryFrameGrabVerdictStore(),
    private val preset: FrameGrabPreset? = null,
    private val bootState: FileGrabBootState = FileGrabBootState(),
) {
    private var verdict: FrameGrabVerdict = store.read(fingerprint) ?: FrameGrabVerdict()

    val canUsePixelCopy: Boolean
        get() = preset?.pixelCopyDisabled?.let { !it } ?: !verdict.pixelCopyDisabled

    val canGrabFromFile: Boolean
        get() = when (val presetValue = preset?.fileGrabDisabled) {
            true -> false
            false -> true
            null -> !bootState.fileGrabDisabled && !verdict.fileGrabDisabled
        }

    /** Called after a successful PixelCopy with the wall-clock duration. */
    fun onSuccessfulGrab(durationMs: Long) {
        if (preset?.pixelCopyDisabled != null) return
        if (durationMs > SUCCESS_BUDGET_MS) {
            update(verdict.copy(pixelCopyDisabled = true))
        }
    }

    /**
     * Called with every analyzed frame. [allBlack] must come from a
     * [FrameLuminance] scan of the 16x9 grid.
     */
    fun onFrameAnalyzed(keyColorArgb: Int, allBlack: Boolean) {
        if (preset?.pixelCopyDisabled != null) return
        if (!allBlack || isNearBlack(keyColorArgb)) {
            if (verdict.consecutiveBlackGrabs != 0) {
                update(verdict.copy(consecutiveBlackGrabs = 0))
            }
            return
        }
        val count = verdict.consecutiveBlackGrabs + 1
        update(
            verdict.copy(
                consecutiveBlackGrabs = count,
                pixelCopyDisabled = verdict.pixelCopyDisabled || count >= BLACK_GRABS_TO_DISABLE,
            ),
        )
    }

    /** Called when MediaMetadataRetriever exceeded the 10 s timeout. */
    fun onFileGrabTimeout() {
        if (preset?.fileGrabDisabled != null) return
        bootState.fileGrabDisabled = true
        update(verdict.copy(fileGrabDisabled = true))
    }

    fun currentVerdict(): FrameGrabVerdict = verdict

    private fun update(next: FrameGrabVerdict) {
        verdict = next
        store.write(fingerprint, next)
    }

    companion object {
        const val SUCCESS_BUDGET_MS = 150L
        const val GRID_COLUMNS = 16
        const val GRID_ROWS = 9
        const val BLACK_LUMA_FRACTION = 0.04
        const val BLACK_GRABS_TO_DISABLE = 2
        const val FILE_GRAB_TIMEOUT_MS = 10_000L

        fun isNearBlack(argb: Int): Boolean = FrameLuminance.luma(argb) < BLACK_LUMA_FRACTION
    }
}

/** Pure 16x9 luminance scan; avoids touching a Bitmap so it is JVM-testable. */
object FrameLuminance {
    fun luma(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }

    fun maxLuminance(pixels: IntArray, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return 0.0
        var max = 0.0
        for (row in 0 until FrameGrabGuard.GRID_ROWS) {
            val y = (((row + 0.5) * height) / FrameGrabGuard.GRID_ROWS)
                .toInt()
                .coerceIn(0, height - 1)
            for (column in 0 until FrameGrabGuard.GRID_COLUMNS) {
                val x = (((column + 0.5) * width) / FrameGrabGuard.GRID_COLUMNS)
                    .toInt()
                    .coerceIn(0, width - 1)
                val value = luma(pixels[y * width + x])
                if (value > max) max = value
            }
        }
        return max
    }

    fun isAllBlack(pixels: IntArray, width: Int, height: Int): Boolean =
        maxLuminance(pixels, width, height) < FrameGrabGuard.BLACK_LUMA_FRACTION
}
