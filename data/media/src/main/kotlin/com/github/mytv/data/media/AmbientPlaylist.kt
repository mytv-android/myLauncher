package com.github.mytv.data.media

import android.content.res.AssetManager
import com.github.mytv.core.ambient.AmbientClip
import com.github.mytv.core.ambient.AmbientKeyframe
import com.github.mytv.core.ambient.AmbientQuality
import com.github.mytv.core.ambient.AmbientSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads `assets/ambient/playlist.json`. Every entry must carry url, title,
 * license and source; the acceptance check S13 enforces those fields.
 */
object AmbientPlaylist {
    const val ASSET_PATH = "ambient/playlist.json"

    fun load(assets: AssetManager): List<AmbientClip> =
        assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

    fun parse(json: String): List<AmbientClip> {
        val root = JSONObject(json)
        val clips = root.optJSONArray("clips") ?: JSONArray()
        return buildList(clips.length()) {
            for (index in 0 until clips.length()) {
                val item = clips.optJSONObject(index) ?: continue
                val url = item.optString("url")
                val title = item.optString("title")
                val license = item.optString("license")
                val source = item.optString("source")
                if (url.isBlank() || title.isBlank() || license.isBlank() || source.isBlank()) continue
                add(
                    AmbientClip(
                        id = item.optString("id").ifBlank { "clip-$index" },
                        url = url,
                        title = title,
                        license = license,
                        source = AmbientSource.fromWire(source),
                        location = item.optString("location"),
                        posterUrl = item.optString("poster").takeIf { it.isNotBlank() },
                        keyColor = parseColor(item.optString("keyColor")),
                        keyframes = parseKeyframes(item.optJSONArray("keyframes")),
                        variants = parseVariants(item.optJSONObject("variants")),
                        localFile = item.optString("file").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("durationMs", 0L),
                    ),
                )
            }
        }
    }

    private fun parseKeyframes(array: JSONArray?): List<AmbientKeyframe> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val image = item.optString("image")
                if (image.isNotBlank()) {
                    add(AmbientKeyframe(item.optLong("positionMs", 0L), image))
                }
            }
        }
    }

    private fun parseVariants(json: JSONObject?): Map<AmbientQuality, String> {
        if (json == null) return emptyMap()
        val result = HashMap<AmbientQuality, String>()
        for (quality in AmbientQuality.entries) {
            val url = json.optString(quality.name)
            if (url.isNotBlank()) result[quality] = url
        }
        return result
    }

    private fun parseColor(raw: String): Int {
        val value = raw.trim().removePrefix("#")
        if (value.length != 6 && value.length != 8) return 0
        return runCatching {
            if (value.length == 6) {
                (0xFF000000L or value.toLong(16)).toInt()
            } else {
                value.toLong(16).toInt()
            }
        }.getOrDefault(0)
    }
}
