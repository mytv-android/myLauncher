package com.github.mytv.data.files

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers T19: resumable uploads, prechecks and temporary-file cleanup (§14).
 */
class ResumableUploadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeStorage(
        var kind: VolumeKind = VolumeKind.INTERNAL,
        var fileSystem: FileSystemKind = FileSystemKind.OTHER,
        var freeBytes: Long = 8L * 1024 * 1024 * 1024,
        var totalBytes: Long = 16L * 1024 * 1024 * 1024,
    ) : StorageFacts {
        override fun volumeOf(target: File): VolumeInfo =
            VolumeInfo("fake", kind, fileSystem, totalBytes, freeBytes)
    }

    @Test
    fun wrongOffsetReturnsConflictWithActualOffset() {
        val dir = temp.newFolder("conflict")
        val upload = ResumableUpload(FakeStorage())
        val begin = upload.begin(dir, "file.bin", 10) as UploadBeginResult.Created
        assertEquals(0L, begin.offset)

        val first = upload.append(begin.id, 0, byteArrayOf(1, 2, 3, 4))
        assertTrue(first is UploadAppendResult.Written)
        assertEquals(4L, (first as UploadAppendResult.Written).offset)

        val conflict = upload.append(begin.id, 2, byteArrayOf(9))
        assertTrue(conflict is UploadAppendResult.Conflict)
        assertEquals(4L, (conflict as UploadAppendResult.Conflict).actualOffset)

        val tooFar = upload.append(begin.id, 9, byteArrayOf(9))
        assertTrue(tooFar is UploadAppendResult.Conflict)
        assertEquals(4L, (tooFar as UploadAppendResult.Conflict).actualOffset)
    }

    @Test
    fun resumeFromHeadOffsetProducesIdenticalFile() {
        val dir = temp.newFolder("resume")
        val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val upload = ResumableUpload(FakeStorage(), bufferSize = 64 * 1024)
        val begin = upload.begin(dir, "resume.bin", data.size.toLong()) as UploadBeginResult.Created

        val half = data.size / 2
        val interrupted = upload.append(begin.id, 0, data.copyOfRange(0, half))
        assertTrue(interrupted is UploadAppendResult.Written)
        assertFalse((interrupted as UploadAppendResult.Written).complete)

        val offset = upload.head(begin.id)
        assertEquals(half.toLong(), offset)

        val rest = upload.append(begin.id, offset!!, data.copyOfRange(half, data.size))
        assertTrue(rest is UploadAppendResult.Written)
        assertTrue((rest as UploadAppendResult.Written).complete)

        assertArrayEquals(data, File(dir, "resume.bin").readBytes())
        assertFalse(File(dir, ".resume.bin.mlpart").exists())
    }

    @Test
    fun completionRenamesAtomicallyAndSetsModificationTime() {
        val dir = temp.newFolder("complete")
        val modified = 1_700_000_000_000L
        val upload = ResumableUpload(FakeStorage())
        val begin = upload.begin(
            dir,
            "done.bin",
            5,
            modifiedTimeMs = modified,
        ) as UploadBeginResult.Created
        assertEquals("done.bin", begin.name)

        val end = upload.append(begin.id, 0, byteArrayOf(7, 8, 9, 10, 11))
        assertTrue(end is UploadAppendResult.Written)
        assertTrue((end as UploadAppendResult.Written).complete)
        assertEquals(File(dir, "done.bin").path, end.path)

        val target = File(dir, "done.bin")
        assertTrue(target.exists())
        assertFalse(File(dir, ".done.bin.mlpart").exists())
        assertArrayEquals(byteArrayOf(7, 8, 9, 10, 11), target.readBytes())
        assertEquals(modified, target.lastModified())
    }

    @Test
    fun internalStorageMustKeepOneGiBAfterPrecheck() {
        val dir = temp.newFolder("internal")
        val size = 10_000L
        val reserve = ResumableUpload.INTERNAL_RESERVE_BYTES

        val tight = FakeStorage(
            kind = VolumeKind.INTERNAL,
            freeBytes = reserve + size - 1,
        )
        val rejected = ResumableUpload(tight).begin(dir, "a.bin", size)
        assertTrue(rejected is UploadBeginResult.Rejected)
        assertEquals(UploadError.NO_SPACE, (rejected as UploadBeginResult.Rejected).error)

        val enough = FakeStorage(
            kind = VolumeKind.INTERNAL,
            freeBytes = reserve + size,
        )
        assertTrue(ResumableUpload(enough).begin(dir, "a.bin", size) is UploadBeginResult.Created)
    }

    @Test
    fun removableStorageMustKeepSixtyFourMiBAfterPrecheck() {
        val dir = temp.newFolder("removable")
        val size = 1_000L
        val reserve = ResumableUpload.REMOVABLE_RESERVE_BYTES

        val tight = FakeStorage(
            kind = VolumeKind.REMOVABLE,
            freeBytes = reserve + size - 1,
        )
        val rejected = ResumableUpload(tight).begin(dir, "a.bin", size)
        assertTrue(rejected is UploadBeginResult.Rejected)
        assertEquals(UploadError.NO_SPACE, (rejected as UploadBeginResult.Rejected).error)

        val enough = FakeStorage(
            kind = VolumeKind.REMOVABLE,
            freeBytes = reserve + size,
        )
        assertTrue(ResumableUpload(enough).begin(dir, "a.bin", size) is UploadBeginResult.Created)
    }

    @Test
    fun fat32RefusesFilesLargerThanFourGiBMinusOne() {
        val dir = temp.newFolder("fat32")
        val storage = FakeStorage(
            kind = VolumeKind.REMOVABLE,
            fileSystem = FileSystemKind.FAT32,
            freeBytes = 8L * 1024 * 1024 * 1024,
            totalBytes = 16L * 1024 * 1024 * 1024,
        )
        val upload = ResumableUpload(storage)

        val tooLarge = upload.begin(dir, "movie.mkv", ResumableUpload.FAT32_MAX_FILE_BYTES + 1)
        assertTrue(tooLarge is UploadBeginResult.Rejected)
        assertEquals(UploadError.TOO_LARGE, (tooLarge as UploadBeginResult.Rejected).error)

        val atLimit = upload.begin(dir, "movie.mkv", ResumableUpload.FAT32_MAX_FILE_BYTES)
        assertTrue(atLimit is UploadBeginResult.Created)
    }

    @Test
    fun spaceRunningOutDuringWriteDeletesTemporaryFile() {
        val dir = temp.newFolder("out-of-space")
        var calls = 0
        val storage = StorageFacts { target ->
            calls++
            VolumeInfo(
                id = "fake",
                kind = VolumeKind.INTERNAL,
                fileSystem = FileSystemKind.OTHER,
                totalBytes = 16L * 1024 * 1024 * 1024,
                freeBytes = if (calls <= 2) 8L * 1024 * 1024 * 1024 else 0L,
            )
        }
        val upload = ResumableUpload(storage, bufferSize = 4)
        val begin = upload.begin(dir, "fail.bin", 8) as UploadBeginResult.Created

        val result = upload.append(begin.id, 0, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(result is UploadAppendResult.OutOfSpace)
        assertEquals(4L, (result as UploadAppendResult.OutOfSpace).actualOffset)
        assertFalse(File(dir, ".fail.bin.mlpart").exists())
        assertFalse(File(dir, "fail.bin").exists())
    }

    @Test
    fun cleanupRemovesPartsOlderThanTwentyFourHours() {
        val now = System.currentTimeMillis()
        val dir = temp.newFolder("cleanup")
        val stale = File(dir, ".old.bin.mlpart").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - ResumableUpload.PART_TTL_MS - 60_000)
        }
        val fresh = File(dir, ".new.bin.mlpart").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(now)
        }
        val keep = File(dir, "normal.bin").apply {
            writeBytes(byteArrayOf(3))
            setLastModified(now - ResumableUpload.PART_TTL_MS - 60_000)
        }

        val upload = ResumableUpload(FakeStorage(), clock = { now })
        val deleted = upload.cleanupExpired(dir)

        assertEquals(listOf(stale), deleted)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(keep.exists())
    }
}
