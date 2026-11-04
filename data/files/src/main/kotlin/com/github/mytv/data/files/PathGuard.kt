package com.github.mytv.data.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Filesystems that change how file names have to be sanitised. */
enum class FileSystemKind {
    FAT32,
    EXFAT,
    OTHER;

    val isFatFamily: Boolean get() = this != OTHER

    companion object {
        fun fromLabel(label: String?): FileSystemKind {
            val normalized = label?.trim()?.lowercase(Locale.ROOT) ?: return OTHER
            return when {
                normalized == "fat" || normalized == "fat32" || normalized.startsWith("vfat") -> FAT32
                normalized == "exfat" || normalized.startsWith("exfat") -> EXFAT
                else -> OTHER
            }
        }
    }
}

/** Result of resolving a relative request path against a root directory. */
sealed class PathResolution {
    data class Ok(val file: File) : PathResolution()
    data class BadRequest(val message: String) : PathResolution()
    data class NotFound(val message: String) : PathResolution()
}

/** Result of making a caller supplied file or directory name safe to store. */
sealed class NameResolution {
    data class Ok(val name: String) : NameResolution()
    data class Rejected(val message: String) : NameResolution()
}

/**
 * Validates and resolves the relative paths used by the phone-to-TV service.
 *
 * The rules follow the design document (§14, "路径与输入校验"):
 *  - the path is URL decoded exactly once, then split on `/`;
 *  - empty segments, `.`, `..`, NUL and backslashes are rejected;
 *  - the canonical target must stay below the canonical root, so symlinks cannot escape;
 *  - `Android/data`, `Android/obb` and caller supplied private directories are not visible;
 *  - names are NFC normalised and limited to 255 UTF-8 bytes;
 *  - names written to FAT/exFAT volumes have reserved characters replaced with `_`.
 */
class PathGuard(
    private val hiddenPaths: List<String> = DEFAULT_HIDDEN_PATHS,
    private val privateDirs: List<File> = emptyList(),
    private val maxNameBytes: Int = MAX_NAME_BYTES,
    private val maxPathBytes: Int = MAX_PATH_BYTES,
    private val canonicalize: (File) -> File = { it.canonicalFile },
) {
    companion object {
        const val MAX_NAME_BYTES = 255
        const val MAX_PATH_BYTES = 1024
        val DEFAULT_HIDDEN_PATHS: List<String> = listOf("Android/data", "Android/obb")

        private val FAT_FORBIDDEN: Set<Char> = setOf('\\', ':', '*', '?', '"', '<', '>', '|')

        /**
         * Decodes percent escapes exactly once. Unlike [java.net.URLDecoder] a `+` is kept
         * as a literal plus, because this input is a path and not a form value.
         */
        internal fun percentDecodeOnce(value: String): String? {
            val out = StringBuilder(value.length)
            val pending = ByteArrayOutputStream()
            var index = 0

            fun flushBytes(): Boolean {
                if (pending.size() == 0) return true
                return try {
                    val decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    out.append(decoder.decode(ByteBuffer.wrap(pending.toByteArray())))
                    pending.reset()
                    true
                } catch (e: CharacterCodingException) {
                    false
                }
            }

            while (index < value.length) {
                val ch = value[index]
                if (ch == '%') {
                    if (index + 2 >= value.length) return null
                    val high = Character.digit(value[index + 1], 16)
                    val low = Character.digit(value[index + 2], 16)
                    if (high < 0 || low < 0) return null
                    pending.write((high shl 4) or low)
                    index += 3
                } else {
                    if (!flushBytes()) return null
                    out.append(ch)
                    index++
                }
            }
            if (!flushBytes()) return null
            return out.toString()
        }
    }

    /**
     * Resolves [path] below [root]. [decode] is `true` when [path] still carries percent
     * escapes; it must be decoded only once, never twice.
     */
    fun resolve(root: File, path: String, decode: Boolean = true): PathResolution {
        if (path.startsWith('/')) return PathResolution.BadRequest("absolute path")
        if (path.indexOf('\u0000') >= 0) return PathResolution.BadRequest("nul byte")
        if (path.indexOf('\\') >= 0) return PathResolution.BadRequest("backslash")

        val decoded = if (decode) {
            percentDecodeOnce(path) ?: return PathResolution.BadRequest("invalid percent encoding")
        } else {
            path
        }

        if (decoded.startsWith('/')) return PathResolution.BadRequest("absolute path")
        if (decoded.indexOf('\u0000') >= 0) return PathResolution.BadRequest("nul byte")
        if (decoded.indexOf('\\') >= 0) return PathResolution.BadRequest("backslash")
        if (decoded.toByteArray(StandardCharsets.UTF_8).size > maxPathBytes) {
            return PathResolution.BadRequest("path too long")
        }

        val rawSegments = if (decoded.isEmpty()) emptyList() else decoded.split('/')
        if (rawSegments.any { it.isEmpty() }) return PathResolution.BadRequest("empty segment")

        val segments = ArrayList<String>(rawSegments.size)
        for (raw in rawSegments) {
            if (raw == "." || raw == "..") return PathResolution.BadRequest("relative segment")
            val segment = Normalizer.normalize(raw, Normalizer.Form.NFC)
            if (segment == "." || segment == "..") return PathResolution.BadRequest("relative segment")
            if (segment.toByteArray(StandardCharsets.UTF_8).size > maxNameBytes) {
                return PathResolution.BadRequest("name too long")
            }
            segments += segment
        }

        val rootCanonical = try {
            canonicalize(root)
        } catch (e: Exception) {
            return PathResolution.BadRequest("unresolvable root")
        }
        val joined = if (segments.isEmpty()) rootCanonical else {
            File(rootCanonical, segments.joinToString(File.separator))
        }
        val candidate = try {
            canonicalize(joined)
        } catch (e: Exception) {
            return PathResolution.NotFound("unresolvable path")
        }
        if (!isInside(rootCanonical, candidate)) return PathResolution.NotFound("outside root")
        if (isHidden(rootCanonical, candidate)) return PathResolution.NotFound("hidden path")
        return PathResolution.Ok(candidate)
    }

    /** True when [file] is below [root] and is not one of the invisible directories. */
    fun isVisible(root: File, file: File): Boolean {
        val rootCanonical = try {
            canonicalize(root)
        } catch (e: Exception) {
            return false
        }
        val candidate = try {
            canonicalize(file)
        } catch (e: Exception) {
            return false
        }
        return isInside(rootCanonical, candidate) && !isHidden(rootCanonical, candidate)
    }

    /** Slash separated path of [file] relative to [root], or null when it is outside. */
    fun relativePath(root: File, file: File): String? {
        val rootCanonical = try {
            canonicalize(root)
        } catch (e: Exception) {
            return null
        }
        val candidate = try {
            canonicalize(file)
        } catch (e: Exception) {
            return null
        }
        if (!isInside(rootCanonical, candidate)) return null
        val rootPath = rootCanonical.absolutePath.trimEnd(File.separatorChar)
        return candidate.absolutePath
            .removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
    }

    /**
     * Normalises a single file or directory name. Path separators and NUL are never
     * allowed; FAT/exFAT volumes additionally replace reserved characters and controls
     * with `_` (§14).
     */
    fun sanitizeName(name: String, fileSystem: FileSystemKind): NameResolution {
        if (name.isEmpty()) return NameResolution.Rejected("empty name")
        if (name == "." || name == "..") return NameResolution.Rejected("relative name")

        val out = StringBuilder(name.length)
        for (ch in name) {
            when {
                ch == '\u0000' || ch == '/' -> return NameResolution.Rejected("illegal character")
                fileSystem.isFatFamily &&
                    (ch in FAT_FORBIDDEN || ch.code < 0x20 || ch.code == 0x7f) -> out.append('_')
                else -> out.append(ch)
            }
        }

        val normalized = Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
        if (normalized.isEmpty()) return NameResolution.Rejected("empty name")
        if (normalized == "." || normalized == "..") return NameResolution.Rejected("relative name")
        if (normalized.toByteArray(StandardCharsets.UTF_8).size > maxNameBytes) {
            return NameResolution.Rejected("name too long")
        }
        return NameResolution.Ok(normalized)
    }

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val candidatePath = candidate.absolutePath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun isHidden(root: File, candidate: File): Boolean {
        if (!isInside(root, candidate)) return true
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val slashRelative = candidate.absolutePath
            .removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
            .lowercase(Locale.ROOT)
        if (slashRelative.isEmpty()) return false
        val hiddenByRule = hiddenPaths.any { hidden ->
            val normalized = hidden.trim('/').lowercase(Locale.ROOT)
            normalized.isNotEmpty() &&
                (slashRelative == normalized || slashRelative.startsWith("$normalized/"))
        }
        if (hiddenByRule) return true
        return privateDirs.any { privateDir ->
            val privateCanonical = try {
                privateDir.canonicalFile
            } catch (e: Exception) {
                privateDir.absoluteFile
            }
            isInside(privateCanonical, candidate)
        }
    }
}
