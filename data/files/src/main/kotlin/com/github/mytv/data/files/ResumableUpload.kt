package com.github.mytv.data.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class UploadError {
    INVALID_NAME,
    NOT_WRITABLE,
    NO_SPACE,
    TOO_LARGE,
}

sealed class UploadBeginResult {
    data class Created(
        val id: String,
        val name: String,
        val offset: Long,
    ) : UploadBeginResult()

    data class Rejected(val error: UploadError, val message: String) : UploadBeginResult()
}

sealed class UploadAppendResult {
    data class Written(
        val offset: Long,
        val complete: Boolean,
        val path: String?,
    ) : UploadAppendResult()

    /** The supplied `Upload-Offset` did not match; [actualOffset] is the real one (409). */
    data class Conflict(val actualOffset: Long) : UploadAppendResult()

    /** Writing hit the free-space limit; the temporary file has been deleted (507). */
    data class OutOfSpace(val actualOffset: Long) : UploadAppendResult()

    data class UnknownSession(val id: String) : UploadAppendResult()

    data class Failed(val message: String, val actualOffset: Long) : UploadAppendResult()
}

/**
 * Resumable uploads with `Upload-Offset` semantics (§14, "上传与断点续传").
 *
 * A body is appended to a hidden `.<name>.mlpart` file next to the target. A wrong
 * offset is reported as [UploadAppendResult.Conflict]; after a network interruption the
 * caller asks [offset] (HEAD) and continues from there. Once the declared size is
 * reached the part file is atomically renamed on the same volume and the requested
 * modification time is applied.
 *
 * Space and filesystem facts come from [StorageFacts] so prechecks are testable:
 *  - internal storage must keep at least 1 GiB free,
 *  - removable storage must keep at least 64 MiB free,
 *  - a FAT32 volume refuses files larger than 4 GiB - 1.
 */
class ResumableUpload(
    private val storage: StorageFacts,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val internalReserveBytes: Long = INTERNAL_RESERVE_BYTES,
    private val removableReserveBytes: Long = REMOVABLE_RESERVE_BYTES,
    private val partTtlMs: Long = PART_TTL_MS,
    private val pathGuard: PathGuard = PathGuard(),
) {
    companion object {
        const val PART_SUFFIX = ".mlpart"
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
        const val INTERNAL_RESERVE_BYTES = 1L * 1024 * 1024 * 1024
        const val REMOVABLE_RESERVE_BYTES = 64L * 1024 * 1024
        const val PART_TTL_MS = 24L * 60 * 60 * 1000
        const val FAT32_MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 - 1

        fun partFileFor(target: File): File =
            File(target.parentFile, ".${target.name}$PART_SUFFIX")

        fun isPartName(name: String): Boolean =
            name.startsWith('.') && name.endsWith(PART_SUFFIX) && name.length > PART_SUFFIX.length + 1
    }

    private data class Session(
        val id: String,
        val name: String,
        val target: File,
        val part: File,
        val size: Long,
        val modifiedTimeMs: Long?,
        val createdAt: Long,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    /** Registers an upload and creates the empty temporary file. */
    fun begin(
        targetDir: File,
        fileName: String,
        size: Long,
        modifiedTimeMs: Long? = null,
    ): UploadBeginResult {
        if (size < 0) return UploadBeginResult.Rejected(UploadError.INVALID_NAME, "negative size")
        val dir = targetDir.absoluteFile
        if (!dir.isDirectory) {
            return UploadBeginResult.Rejected(UploadError.NOT_WRITABLE, "not a directory")
        }
        if (!dir.canWrite()) {
            return UploadBeginResult.Rejected(UploadError.NOT_WRITABLE, "directory not writable")
        }

        val volume = storage.volumeOf(dir)
        val name = when (val sanitized = pathGuard.sanitizeName(fileName, volume.fileSystem)) {
            is NameResolution.Ok -> sanitized.name
            is NameResolution.Rejected ->
                return UploadBeginResult.Rejected(UploadError.INVALID_NAME, sanitized.message)
        }

        if (volume.fileSystem == FileSystemKind.FAT32 && size > FAT32_MAX_FILE_BYTES) {
            return UploadBeginResult.Rejected(UploadError.TOO_LARGE, "file exceeds FAT32 limit")
        }
        if (!hasRoom(volume, size)) {
            return UploadBeginResult.Rejected(UploadError.NO_SPACE, "not enough free space")
        }

        val target = File(dir, name)
        val part = partFileFor(target)
        return try {
            if (part.exists() && !part.delete()) {
                return UploadBeginResult.Rejected(UploadError.NOT_WRITABLE, "cannot replace stale part")
            }
            FileOutputStream(part).use { }
            part.setLastModified(clock())
            val id = UUID.randomUUID().toString()
            sessions[id] = Session(id, name, target, part, size, modifiedTimeMs?.takeIf { it > 0 }, clock())
            UploadBeginResult.Created(id, name, 0)
        } catch (e: IOException) {
            part.delete()
            UploadBeginResult.Rejected(UploadError.NOT_WRITABLE, e.message ?: "cannot create part file")
        }
    }

    /** Current offset of [id], or null when the session is unknown (HEAD semantics). */
    fun offset(id: String): Long? = sessions[id]?.part?.length()

    fun head(id: String): Long? = offset(id)

    fun declaredSize(id: String): Long? = sessions[id]?.size

    fun append(id: String, expectedOffset: Long, bytes: ByteArray): UploadAppendResult =
        append(id, expectedOffset, bytes.inputStream(), bytes.size.toLong())

    /**
     * Appends [contentLength] bytes from [input] at [expectedOffset]. A negative
     * [contentLength] means "read until EOF". The stream is not closed by this method.
     */
    fun append(
        id: String,
        expectedOffset: Long,
        input: InputStream,
        contentLength: Long = -1,
    ): UploadAppendResult {
        val session = sessions[id] ?: return UploadAppendResult.UnknownSession(id)
        synchronized(session) {
            if (!sessions.containsKey(id)) return UploadAppendResult.UnknownSession(id)
            val actual = session.part.length()
            if (expectedOffset != actual) return UploadAppendResult.Conflict(actual)
            if (actual > session.size) {
                deleteSession(session)
                return UploadAppendResult.Failed("upload exceeds declared size", actual)
            }
            if (actual == session.size) return finalize(session)

            val remaining = session.size - actual
            if (contentLength > remaining) {
                return UploadAppendResult.Failed("body longer than declared size", actual)
            }

            var written = actual
            var toRead = remaining
            val out = try {
                FileOutputStream(session.part, true)
            } catch (e: IOException) {
                return UploadAppendResult.Failed(e.message ?: "cannot open part file", actual)
            }
            try {
                val chunkSize = minOf(bufferSize.toLong(), toRead, Int.MAX_VALUE.toLong())
                    .toInt().coerceAtLeast(1)
                val buffer = ByteArray(chunkSize)
                while (toRead > 0) {
                    val free = freeBytesOf(session)
                    val want = minOf(buffer.size.toLong(), toRead).toInt()
                    if (free in 0 until want.toLong()) {
                        out.close()
                        deleteSession(session)
                        return UploadAppendResult.OutOfSpace(written)
                    }
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    written += read
                    toRead -= read
                }
                out.flush()
            } catch (e: IOException) {
                if (isOutOfSpace(e)) {
                    try {
                        out.close()
                    } catch (ignored: IOException) {
                    }
                    deleteSession(session)
                    return UploadAppendResult.OutOfSpace(written)
                }
                return UploadAppendResult.Failed(e.message ?: "write failed", written)
            } finally {
                try {
                    out.close()
                } catch (ignored: IOException) {
                }
            }

            if (written < session.size) {
                val offsetNow = session.part.length()
                return UploadAppendResult.Written(offsetNow, false, null)
            }
            if (contentLength >= 0) {
                // The body length was declared, so do not touch the stream again: any
                // following byte belongs to the next request on a keep-alive socket.
                return finalize(session)
            }
            val extra = try {
                input.read()
            } catch (e: IOException) {
                -1
            }
            if (extra != -1) {
                deleteSession(session)
                return UploadAppendResult.Failed("body longer than declared size", written)
            }
            return finalize(session)
        }
    }

    /** Cancels an upload and removes its temporary file. */
    fun cancel(id: String): Boolean {
        val session = sessions[id] ?: return false
        synchronized(session) {
            sessions.remove(id)
            session.part.delete()
        }
        return true
    }

    /**
     * Deletes `.mlpart` files older than the 24 hour TTL. [directory] is scanned
     * non-recursively unless [recursive] is set. Parts owned by live sessions are kept.
     */
    fun cleanupExpired(
        directory: File,
        now: Long = clock(),
        recursive: Boolean = false,
    ): List<File> {
        val activeParts = HashSet<String>()
        for (session in sessions.values) {
            activeParts += session.part.absolutePath
        }

        val deleted = ArrayList<File>()
        val pending = ArrayList<File>()
        pending += directory
        val visited = HashSet<String>()
        val cutoff = now - partTtlMs
        val rootCanonical = try {
            directory.canonicalPath
        } catch (e: IOException) {
            directory.absolutePath
        }

        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.size - 1)
            val key = try {
                current.canonicalPath
            } catch (e: IOException) {
                current.absolutePath
            }
            if (!visited.add(key)) continue
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (recursive && isWithin(rootCanonical, child)) pending += child
                    continue
                }
                if (!isPartName(child.name)) continue
                if (child.absolutePath in activeParts) continue
                if (child.lastModified() >= cutoff) continue
                if (child.delete()) deleted += child
            }
        }

        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().value.part.exists()) iterator.remove()
        }
        return deleted
    }

    private fun hasRoom(volume: VolumeInfo, size: Long): Boolean {
        val free = volume.freeBytes
        if (free < 0) return true
        val reserve = when (volume.kind) {
            VolumeKind.INTERNAL -> internalReserveBytes
            VolumeKind.REMOVABLE -> removableReserveBytes
        }
        return free >= size && free - size >= reserve
    }

    private fun freeBytesOf(session: Session): Long = try {
        storage.volumeOf(session.target.parentFile ?: session.target).freeBytes
    } catch (e: Exception) {
        -1
    }

    private fun finalize(session: Session): UploadAppendResult {
        val part = session.part
        val target = session.target
        return try {
            // The part file lives in the target directory, so renameTo stays on one
            // volume and is atomic on POSIX. Windows refuses to overwrite the target.
            if (target.exists() && !target.delete()) {
                return UploadAppendResult.Failed("cannot replace target", part.length())
            }
            if (!part.renameTo(target)) {
                return UploadAppendResult.Failed("rename failed", part.length())
            }
            session.modifiedTimeMs?.let { target.setLastModified(it) }
            sessions.remove(session.id)
            UploadAppendResult.Written(session.size, true, target.path)
        } catch (e: SecurityException) {
            UploadAppendResult.Failed(e.message ?: "rename failed", part.length())
        }
    }

    private fun isWithin(rootCanonical: String, file: File): Boolean {
        val path = try {
            file.canonicalPath
        } catch (e: IOException) {
            return false
        }
        val rootPath = rootCanonical.trimEnd(File.separatorChar)
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    private fun deleteSession(session: Session) {
        sessions.remove(session.id)
        session.part.delete()
    }

    private fun isOutOfSpace(error: IOException): Boolean {
        val message = error.message?.lowercase() ?: return false
        return "no space" in message ||
            "not enough space" in message ||
            "enospc" in message ||
            "disk full" in message ||
            "insufficient storage" in message
    }
}
