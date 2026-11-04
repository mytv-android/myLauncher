package com.github.mytv.data.media

/**
 * Upload checks shared with the phone/computer transfer module. The HTTP
 * server itself lives in :feature:phone-organizer; this is the policy hook it
 * calls before accepting a body.
 */
object PersonalVideoPolicy {
    const val MAX_VIDEO_BYTES: Long = 2L * 1024 * 1024 * 1024

    sealed interface LengthCheck {
        data object Accepted : LengthCheck

        /** Content-Length is absent; the server must monitor the stream. */
        data object Unknown : LengthCheck

        data class TooLarge(val declaredBytes: Long, val limitBytes: Long) : LengthCheck
    }

    data class TrackCheck(
        val accepted: Boolean,
        val hasVideoTrack: Boolean,
    )

    /** Content-Length precheck; an absent header is not an automatic reject. */
    fun precheckLength(
        contentLength: Long?,
        limitBytes: Long = MAX_VIDEO_BYTES,
    ): LengthCheck = when {
        contentLength == null || contentLength < 0 -> LengthCheck.Unknown
        contentLength > limitBytes -> LengthCheck.TooLarge(contentLength, limitBytes)
        else -> LengthCheck.Accepted
    }

    /**
     * Rejects a completed temporary upload with no video track. The caller
     * deletes the temporary file when [TrackCheck.accepted] is false.
     */
    fun checkTracks(hasVideoTrack: Boolean): TrackCheck =
        TrackCheck(accepted = hasVideoTrack, hasVideoTrack = hasVideoTrack)
}
