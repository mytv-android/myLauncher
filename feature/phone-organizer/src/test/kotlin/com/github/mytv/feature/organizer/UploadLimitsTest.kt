package com.github.mytv.feature.organizer

import com.github.mytv.data.files.FileSystemKind
import com.github.mytv.data.files.ResumableUpload
import com.github.mytv.data.files.StorageFacts
import com.github.mytv.data.files.UploadAppendResult
import com.github.mytv.data.files.VolumeInfo
import com.github.mytv.data.files.VolumeKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadLimitsTest {
    companion object {
        private const val GIB = 1024L * 1024 * 1024
    }

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun singleFileIsAtMostTwoGiB() {
        assertTrue(UploadLimits.checkSingle(2 * GIB).allowed)
        assertEquals(UploadLimitError.TOO_LARGE, UploadLimits.checkSingle(2 * GIB + 1).error)
        assertEquals(UploadLimitError.TOO_LARGE, UploadLimits.checkSingle(-1).error)
    }

    @Test
    fun setTotalIsAtMostHalfOfUsedPlusFree() {
        val used = 2 * GIB
        val free = 6 * GIB
        assertTrue(UploadLimits.checkTotal(used, free, 4 * GIB).allowed)
        assertEquals(
            UploadLimitError.TOTAL_LIMIT,
            UploadLimits.checkTotal(used, free, 4 * GIB + 1).error,
        )
        assertEquals(
            UploadLimitError.TOTAL_LIMIT,
            UploadLimits.checkTotal(used, free, 1 * GIB, alreadyUploadedBytes = 4 * GIB).error,
        )
    }

    @Test
    fun freeSpaceAfterWritingMustStayAboveOneGiB() {
        val used = 2 * GIB
        val free = 2_560L * 1024 * 1024
        val check = UploadLimits.checkTotal(used, free, 1_600L * 1024 * 1024)
        assertEquals(UploadLimitError.FREE_SPACE, check.error)
        assertFalse(check.allowed)
    }

    @Test
    fun atMostSixtyFourSegments() {
        assertTrue(UploadLimits.checkSegments(63).allowed)
        assertEquals(UploadLimitError.TOO_MANY_SEGMENTS, UploadLimits.checkSegments(64).error)
        assertEquals(UploadLimitError.TOO_MANY_SEGMENTS, UploadLimits.checkSegments(63, added = 2).error)
    }

    @Test
    fun contentLengthIsCheckedBeforeTheBody() {
        assertTrue(UploadLimits.checkContentLength(null).allowed)
        assertTrue(UploadLimits.checkContentLength(2 * GIB).allowed)
        assertEquals(
            UploadLimitError.TOO_LARGE,
            UploadLimits.checkContentLength(3 * GIB).error,
        )
        assertTrue(UploadLimits.exceededMidTransfer(11, 10))
        assertFalse(UploadLimits.exceededMidTransfer(10, 10))
    }

    @Test
    fun midTransferOverflowAbortsAndDeletesThePartFile() {
        val directory = temporary.newFolder("uploads")
        val storage = StorageFacts {
            VolumeInfo("primary", VolumeKind.INTERNAL, FileSystemKind.OTHER, 32 * GIB, 24 * GIB)
        }
        val uploads = ResumableUpload(storage)
        val begin = uploads.begin(directory, "clip.bin", 8)
        assertTrue(begin is com.github.mytv.data.files.UploadBeginResult.Created)
        val id = (begin as com.github.mytv.data.files.UploadBeginResult.Created).id
        val part = ResumableUpload.partFileFor(File(directory, "clip.bin"))
        assertTrue(part.exists())
        val result = uploads.append(id, 0, ByteArray(16).inputStream(), contentLength = 16)
        assertTrue(result is UploadAppendResult.Failed)
        uploads.cancel(id)
        assertFalse(part.exists())
    }

    @Test
    fun personalVideoWithoutVideoTrackIsRejected() {
        assertEquals(
            UploadLimitError.NO_VIDEO_TRACK,
            UploadLimits.checkVideoTrack(false).error,
        )
        assertTrue(UploadLimits.checkVideoTrack(true).allowed)
    }
}
