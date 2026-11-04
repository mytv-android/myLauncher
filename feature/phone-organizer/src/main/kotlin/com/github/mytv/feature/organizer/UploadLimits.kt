package com.github.mytv.feature.organizer

/** Machine readable reasons for refusing an upload (§13 / §14 limits). */
enum class UploadLimitError {
    TOO_LARGE,
    TOTAL_LIMIT,
    FREE_SPACE,
    TOO_MANY_SEGMENTS,
    NO_VIDEO_TRACK,
}

data class UploadLimitCheck(
    val allowed: Boolean,
    val error: UploadLimitError? = null,
) {
    companion object {
        val OK = UploadLimitCheck(true)
    }
}

/**
 * Upload prechecks shared by the file browser, the push queue and personal videos.
 *
 *  - a single file is at most 2 GiB;
 *  - the total of the uploaded set is at most half of (already stored + free), and at
 *    least 1 GiB must stay free after the write;
 *  - at most 64 personal video segments;
 *  - `Content-Length` is checked before any body byte is read;
 *  - a body that grows past the declared size aborts and deletes its temporary file;
 *  - a personal video without a decodable video track is refused.
 */
object UploadLimits {
    const val MAX_SINGLE_BYTES = 2L * 1024 * 1024 * 1024
    const val MIN_FREE_AFTER_BYTES = 1L * 1024 * 1024 * 1024
    const val MAX_SEGMENTS = 64

    fun checkSingle(size: Long): UploadLimitCheck {
        if (size < 0) return UploadLimitCheck(false, UploadLimitError.TOO_LARGE)
        if (size > MAX_SINGLE_BYTES) return UploadLimitCheck(false, UploadLimitError.TOO_LARGE)
        return UploadLimitCheck.OK
    }

    /**
     * Checked from the request header before the body is consumed. A missing header is
     * not a size violation; the caller answers 411 instead.
     */
    fun checkContentLength(declared: Long?): UploadLimitCheck {
        if (declared == null) return UploadLimitCheck.OK
        return checkSingle(declared)
    }

    /**
     * [usedBytes] is the data already stored on the volume, [freeBytes] the free space.
     * [alreadyUploadedBytes] is the sum of earlier files of the same set (for example
     * the personal video playlist), [incomingBytes] the file being admitted.
     */
    fun checkTotal(
        usedBytes: Long,
        freeBytes: Long,
        incomingBytes: Long,
        alreadyUploadedBytes: Long = 0,
    ): UploadLimitCheck {
        if (incomingBytes < 0 || alreadyUploadedBytes < 0) {
            return UploadLimitCheck(false, UploadLimitError.TOO_LARGE)
        }
        val capacity = usedBytes + freeBytes
        if (capacity > 0 && alreadyUploadedBytes + incomingBytes > capacity / 2) {
            return UploadLimitCheck(false, UploadLimitError.TOTAL_LIMIT)
        }
        if (freeBytes - incomingBytes < MIN_FREE_AFTER_BYTES) {
            return UploadLimitCheck(false, UploadLimitError.FREE_SPACE)
        }
        return UploadLimitCheck.OK
    }

    fun checkSegments(existingSegments: Int, added: Int = 1): UploadLimitCheck {
        if (existingSegments < 0 || added < 0 || existingSegments + added > MAX_SEGMENTS) {
            return UploadLimitCheck(false, UploadLimitError.TOO_MANY_SEGMENTS)
        }
        return UploadLimitCheck.OK
    }

    fun checkVideoTrack(hasVideoTrack: Boolean): UploadLimitCheck =
        if (hasVideoTrack) UploadLimitCheck.OK
        else UploadLimitCheck(false, UploadLimitError.NO_VIDEO_TRACK)

    /** True once a body has written more bytes than it declared. */
    fun exceededMidTransfer(written: Long, declared: Long): Boolean = written > declared
}
