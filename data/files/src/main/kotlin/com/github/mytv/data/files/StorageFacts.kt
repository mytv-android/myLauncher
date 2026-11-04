package com.github.mytv.data.files

import java.io.File

/** How a storage root behaves for upload prechecks (internal storage vs. removable). */
enum class VolumeKind { INTERNAL, REMOVABLE }

/** Snapshot of the volume that stores a file. All values are injectable for tests. */
data class VolumeInfo(
    val id: String,
    val kind: VolumeKind,
    val fileSystem: FileSystemKind,
    val totalBytes: Long,
    val freeBytes: Long,
)

/**
 * Supplies volume facts to [ResumableUpload]. Production implementations derive this
 * from StorageManager / StatFs; unit tests use a deterministic fake.
 */
fun interface StorageFacts {
    fun volumeOf(target: File): VolumeInfo
}
