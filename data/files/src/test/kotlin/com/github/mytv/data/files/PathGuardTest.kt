package com.github.mytv.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers T18: request-path validation for the phone-to-TV service (§14).
 */
class PathGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun rejectsEmptySegmentsDotDotNulBackslashAndAbsolutePaths() {
        val root = temp.newFolder("root")
        val guard = PathGuard()

        assertTrue(guard.resolve(root, "") is PathResolution.Ok)
        assertTrue(guard.resolve(root, "/etc/passwd") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a//b") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a/") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a/./b") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a/../b") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "..") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a/\u0000b") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a\\b") is PathResolution.BadRequest)
    }

    @Test
    fun decodesUrlExactlyOnce() {
        val root = temp.newFolder("root")
        val guard = PathGuard()

        // One decode turns %2e%2e into "..", which is rejected.
        assertTrue(guard.resolve(root, "%2e%2e/secret") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "a/%2E%2E/b") is PathResolution.BadRequest)

        // %252e%252e decodes once to the literal name "%2e%2e"; it is not decoded again.
        val once = guard.resolve(root, "%252e%252e")
        assertTrue(once is PathResolution.Ok)
        assertEquals("%2e%2e", (once as PathResolution.Ok).file.name)

        val spaced = guard.resolve(root, "a%20b.txt")
        assertTrue(spaced is PathResolution.Ok)
        assertEquals("a b.txt", (spaced as PathResolution.Ok).file.name)

        val utf8 = guard.resolve(root, "%E4%B8%AD.txt")
        assertTrue(utf8 is PathResolution.Ok)
        assertEquals("中.txt", (utf8 as PathResolution.Ok).file.name)

        assertTrue(guard.resolve(root, "%zz") is PathResolution.BadRequest)
        assertTrue(guard.resolve(root, "%5C..") is PathResolution.BadRequest)
    }

    @Test
    fun symlinkEscapeIsNotVisible() {
        val root = File(temp.root, "visible").apply { mkdirs() }
        val outside = File(temp.root, "outside").apply { mkdirs() }

        // Simulate a symlink at <root>/link that resolves outside the root. The
        // canonicalizer stands in for File.canonicalFile so the test is portable.
        val guard = PathGuard(canonicalize = { file ->
            val normalized = file.absolutePath.replace('\\', '/')
            if (normalized.endsWith("/visible/link") ||
                normalized.contains("/visible/link/")
            ) {
                outside
            } else {
                file.absoluteFile
            }
        })

        val escaped = guard.resolve(root, "link")
        assertTrue(escaped is PathResolution.NotFound)
        assertTrue(guard.resolve(root, "link/nested/file") is PathResolution.NotFound)

        val inside = guard.resolve(root, "ok.txt")
        assertTrue(inside is PathResolution.Ok)
    }

    @Test
    fun hidesAndroidDataObbAndPrivateDirectories() {
        val root = temp.newFolder("root")
        File(root, "Android/data/com.example").mkdirs()
        File(root, "Android/obb/com.example").mkdirs()
        File(root, "Android/media/com.example").mkdirs()
        File(root, "Download").mkdirs()
        val privateDir = File(root, "launcher-private").apply { mkdirs() }

        val guard = PathGuard(privateDirs = listOf(privateDir))

        assertTrue(guard.resolve(root, "Android/data") is PathResolution.NotFound)
        assertTrue(guard.resolve(root, "Android/data/com.example") is PathResolution.NotFound)
        assertTrue(guard.resolve(root, "android/DATA/com.example") is PathResolution.NotFound)
        assertTrue(guard.resolve(root, "Android/obb/com.example") is PathResolution.NotFound)
        assertTrue(guard.resolve(root, "Android/obb/com.example/main.1.obb") is PathResolution.NotFound)
        assertTrue(guard.resolve(root, "launcher-private/db") is PathResolution.NotFound)

        assertTrue(guard.resolve(root, "Android/media/com.example") is PathResolution.Ok)
        assertTrue(guard.resolve(root, "Download") is PathResolution.Ok)

        assertFalse(guard.isVisible(root, File(root, "Android/data/com.example")))
        assertFalse(guard.isVisible(root, File(root, "launcher-private")))
        assertTrue(guard.isVisible(root, File(root, "Download")))
        assertNotNull(guard.relativePath(root, File(root, "Download")))
        assertEquals("Download", guard.relativePath(root, File(root, "Download")))
    }

    @Test
    fun normalizesNfcAndEnforcesUtf8NameLimit() {
        val root = temp.newFolder("root")
        val guard = PathGuard()

        val decomposed = "é.txt"
        val resolved = guard.resolve(root, decomposed)
        assertTrue(resolved is PathResolution.Ok)
        assertEquals("é.txt", (resolved as PathResolution.Ok).file.name)

        val sanitized = guard.sanitizeName("é.txt", FileSystemKind.OTHER)
        assertTrue(sanitized is NameResolution.Ok)
        assertEquals("é.txt", (sanitized as NameResolution.Ok).name)

        // 255 UTF-8 bytes is allowed, 256 is not.
        assertTrue(guard.sanitizeName("a".repeat(255), FileSystemKind.OTHER) is NameResolution.Ok)
        assertTrue(guard.sanitizeName("a".repeat(256), FileSystemKind.OTHER) is NameResolution.Rejected)
        assertTrue(guard.resolve(root, "a".repeat(256)) is PathResolution.BadRequest)

        // 85 CJK characters are exactly 255 bytes; 86 overflow.
        assertTrue(guard.sanitizeName("中".repeat(85), FileSystemKind.OTHER) is NameResolution.Ok)
        assertTrue(guard.sanitizeName("中".repeat(86), FileSystemKind.OTHER) is NameResolution.Rejected)
    }

    @Test
    fun sanitizesFatAndExfatNamesButKeepsOtherVolumes() {
        val guard = PathGuard()

        val fat = guard.sanitizeName("a\\b:c*?\"<>|d\u0001e", FileSystemKind.FAT32)
        assertTrue(fat is NameResolution.Ok)
        val fatName = (fat as NameResolution.Ok).name
        assertTrue(fatName.startsWith("a_b_c"))
        assertFalse(
            fatName.any {
                it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' ||
                    it == '<' || it == '>' || it == '|' || it.code < 0x20 || it.code == 0x7f
            },
        )

        assertEquals("x_y", (guard.sanitizeName("x:y", FileSystemKind.EXFAT) as NameResolution.Ok).name)
        assertEquals("x:y", (guard.sanitizeName("x:y", FileSystemKind.OTHER) as NameResolution.Ok).name)
        assertEquals("a\\b", (guard.sanitizeName("a\\b", FileSystemKind.OTHER) as NameResolution.Ok).name)

        assertTrue(guard.sanitizeName("a/b", FileSystemKind.OTHER) is NameResolution.Rejected)
        assertTrue(guard.sanitizeName("a\u0000b", FileSystemKind.OTHER) is NameResolution.Rejected)
        assertTrue(guard.sanitizeName(".", FileSystemKind.OTHER) is NameResolution.Rejected)
        assertTrue(guard.sanitizeName("..", FileSystemKind.OTHER) is NameResolution.Rejected)
    }
}
