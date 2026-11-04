package com.github.mytv.data.media

import android.content.Context
import android.content.SharedPreferences
import com.github.mytv.core.ambient.AmbientResumePoint
import com.github.mytv.core.ambient.AmbientResumeSink
import com.github.mytv.core.ambient.ScreensaverHandoff
import java.util.Calendar
import java.util.Locale

/** Rotation remembers which clips are in the local three-clip set. */
data class AmbientRotation(
    val day: String = "",
    val clipIds: List<String> = emptyList(),
)

data class AmbientResumeState(
    val point: AmbientResumePoint? = null,
    val rotation: AmbientRotation = AmbientRotation(),
)

interface AmbientResumeStore {
    fun read(): AmbientResumeState
    fun write(state: AmbientResumeState)
}

/**
 * Resume, rotation and screensaver handoff rules from §13.
 *
 * The clip id and position are written every 10 seconds and whenever ambient
 * mode is left. The local set holds three clips and rolls one clip per day.
 */
class AmbientResume(
    private val store: AmbientResumeStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastSavedAtMillis: Long? = null

    fun resumePoint(): AmbientResumePoint? = store.read().point

    /** Adapter for AmbientPlaybackController's periodic save callback. */
    fun asSink(): AmbientResumeSink = AmbientResumeSink { clipId, positionMs ->
        onPosition(clipId, positionMs)
    }

    /**
     * Saves at most once per 10 seconds for the same clip; a clip change is
     * saved immediately because resuming another clip must not wait.
     */
    fun onPosition(clipId: String, positionMs: Long): Boolean {
        val now = clock()
        val state = store.read()
        val point = state.point
        val clipChanged = point?.clipId != clipId
        val lastSave = lastSavedAtMillis
        if (!clipChanged && lastSave != null && now - lastSave < SAVE_INTERVAL_MS) return false
        store.write(state.copy(point = AmbientResumePoint(clipId, positionMs, now)))
        lastSavedAtMillis = now
        return true
    }

    /** Leaving ambient mode always writes the current position. */
    fun saveOnLeave(clipId: String, positionMs: Long) {
        val now = clock()
        val state = store.read()
        store.write(state.copy(point = AmbientResumePoint(clipId, positionMs, now)))
        lastSavedAtMillis = now
    }

    /**
     * Returns the clip ids that form today's three-clip set, oldest first.
     * On a new day one clip not currently in the set is appended and the
     * oldest is dropped.
     */
    fun rotationFor(allClipIds: List<String>, day: String = dayKey(clock())): List<String> {
        if (allClipIds.isEmpty()) return emptyList()
        val state = store.read()
        val target = minOf(ROTATION_SIZE, allClipIds.size)
        var current = state.rotation.clipIds.filter { it in allClipIds }.takeLast(target)
        if (current.isEmpty()) {
            current = allClipIds.take(target)
            store.write(state.copy(rotation = AmbientRotation(day, current)))
            return current
        }
        if (state.rotation.day == day && current.size == target) return current

        while (current.size > target) current = current.drop(1)
        if (target > 0 && current.size == target && allClipIds.size > target) {
            val candidates = allClipIds.filter { it !in current }
            if (candidates.isNotEmpty()) {
                val pick = candidates[(day.hashCode().toLong() and 0x7FFFFFFF).rem(candidates.size).toInt()]
                current = current.drop(1) + pick
            }
        }
        store.write(state.copy(rotation = AmbientRotation(day, current)))
        return current
    }

    /**
     * Entering the in-process screensaver while ambient video is playing
     * reuses the same player unless the user selected a different quality.
     */
    fun screensaverHandoff(fromAmbient: Boolean, qualityChanged: Boolean): ScreensaverHandoff =
        if (fromAmbient && !qualityChanged) {
            ScreensaverHandoff.REUSE_SAME_PLAYER
        } else {
            ScreensaverHandoff.RESTART
        }

    companion object {
        const val SAVE_INTERVAL_MS = 10_000L
        const val ROTATION_SIZE = 3

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

/** SharedPreferences-backed store used by the launcher process. */
class SharedPreferencesAmbientResumeStore(context: Context) : AmbientResumeStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): AmbientResumeState {
        val clipId = preferences.getString(KEY_CLIP_ID, null)
        val point = if (clipId.isNullOrBlank()) {
            null
        } else {
            AmbientResumePoint(
                clipId = clipId,
                positionMs = preferences.getLong(KEY_POSITION_MS, 0L),
                savedAtMillis = preferences.getLong(KEY_SAVED_AT, 0L),
            )
        }
        val ids = preferences.getString(KEY_ROTATION_IDS, null)
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return AmbientResumeState(
            point = point,
            rotation = AmbientRotation(
                day = preferences.getString(KEY_ROTATION_DAY, "").orEmpty(),
                clipIds = ids,
            ),
        )
    }

    override fun write(state: AmbientResumeState) {
        val editor = preferences.edit()
        val point = state.point
        if (point == null) {
            editor.remove(KEY_CLIP_ID).remove(KEY_POSITION_MS).remove(KEY_SAVED_AT)
        } else {
            editor.putString(KEY_CLIP_ID, point.clipId)
                .putLong(KEY_POSITION_MS, point.positionMs)
                .putLong(KEY_SAVED_AT, point.savedAtMillis)
        }
        editor.putString(KEY_ROTATION_DAY, state.rotation.day)
            .putString(KEY_ROTATION_IDS, state.rotation.clipIds.joinToString("\u0001"))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "launcher_settings"
        const val KEY_CLIP_ID = "ambient_resume_clip"
        const val KEY_POSITION_MS = "ambient_resume_position"
        const val KEY_SAVED_AT = "ambient_resume_saved_at"
        const val KEY_ROTATION_DAY = "ambient_rotation_day"
        const val KEY_ROTATION_IDS = "ambient_rotation_ids"
    }
}
