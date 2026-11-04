package com.github.mytv.core.ambient

import androidx.compose.ui.graphics.ImageBitmap

/** Playback source values match the acceptance state contract. */
enum class AmbientSource(val wireName: String) {
    AERIAL("aerial"),
    OWN("own"),
    PHOTO("photo"),
    BING("bing"),
    INPUT("input"),
    WALLPAPER("wallpaper"),
    ;

    companion object {
        fun fromWire(value: String?): AmbientSource =
            entries.firstOrNull { it.wireName == value } ?: WALLPAPER
    }
}

/** Player states shown in the acceptance state interface. */
enum class AmbientPlayerStatus(val wireName: String) {
    NONE("none"),
    PLAYING("playing"),
    PAUSED("paused"),
    RELEASED("released"),
}

/** Decoder quality ladder used for the in-process downgrade rule of §13. */
enum class AmbientQuality(val level: Int) {
    DOLBY_VISION_4K(3),
    HEVC_4K(2),
    H264_1080P(1),
    ;

    fun lower(): AmbientQuality? = entries.firstOrNull { it.level == level - 1 }
}

/** One pre-rendered still published with a playlist clip. */
data class AmbientKeyframe(
    val positionMs: Long,
    val imageUri: String,
)

/**
 * A playable ambient clip. The aerial playlist stores id, url, title, license,
 * source, a poster/keyframe and a key color; own videos may only have a local
 * file and a key color calculated after upload.
 */
data class AmbientClip(
    val id: String,
    val url: String,
    val title: String = "",
    val license: String = "",
    val source: AmbientSource = AmbientSource.AERIAL,
    val location: String = "",
    val posterUrl: String? = null,
    val keyColor: Int = 0,
    val keyframes: List<AmbientKeyframe> = emptyList(),
    val variants: Map<AmbientQuality, String> = emptyMap(),
    val localFile: String? = null,
    val durationMs: Long = 0,
) {
    fun urlFor(quality: AmbientQuality): String = variants[quality] ?: url
}

/** Weather passed in by the host; condition text comes from :data:weather. */
data class AmbientWeather(
    val tempC: Int,
    val condition: String,
    val sky: AmbientSky = AmbientSky.CLEAR,
)

/** Icon category, mapped from the weather module's WMO classification. */
enum class AmbientSky {
    CLEAR,
    PARTLY,
    OVERCAST,
    FOG,
    RAIN,
    SNOW,
    THUNDER,
}

/** Clock mode comes from Settings.System.TIME_12_24 via :data:weather. */
data class AmbientClock(val is24h: Boolean = true)

/** Random per-minute offset that protects the panel from burn-in. */
data class DriftOffset(val xDp: Float = 0f, val yDp: Float = 0f) {
    companion object {
        val Zero = DriftOffset()
    }
}

/** Persisted playback position, saved by the host through an [AmbientResumeSink]. */
data class AmbientResumePoint(
    val clipId: String,
    val positionMs: Long,
    val savedAtMillis: Long,
)

/** Receives periodic resume points; :data:media implements the storage side. */
fun interface AmbientResumeSink {
    fun save(clipId: String, positionMs: Long)
}

/** Result of handing the current session to the in-process screensaver. */
enum class ScreensaverHandoff {
    REUSE_SAME_PLAYER,
    RESTART,
}

/** What the playback controller does after a clip fails. */
enum class AmbientFailureAction {
    NEXT_CLIP,
    PHOTOS_OR_WALLPAPER,
}

/** One decoded frame captured from the video surface or a file fallback. */
class FrameGrabResult(
    val bitmap: android.graphics.Bitmap,
    val keyColor: Int,
    val allBlack: Boolean,
    val elapsedMs: Long,
)

/**
 * Everything [AmbientScreen] needs. The controller owns the source, clip and
 * player fields; the host supplies clock/weather/lunar data.
 */
data class AmbientState(
    val source: AmbientSource = AmbientSource.WALLPAPER,
    val clip: AmbientClip? = null,
    val positionMs: Long = 0,
    val player: AmbientPlayerStatus = AmbientPlayerStatus.NONE,
    val clock: AmbientClock = AmbientClock(),
    val weather: AmbientWeather? = null,
    val lunarText: String? = null,
    val drift: DriftOffset = DriftOffset.Zero,
    val dimmed: Boolean = false,
    val stillFrame: ImageBitmap? = null,
    val showInfo: Boolean = true,
) {
    val videoSurfaceVisible: Boolean
        get() = clip != null &&
            (player == AmbientPlayerStatus.PLAYING || player == AmbientPlayerStatus.PAUSED) &&
            (source == AmbientSource.AERIAL || source == AmbientSource.OWN || source == AmbientSource.INPUT)
}
