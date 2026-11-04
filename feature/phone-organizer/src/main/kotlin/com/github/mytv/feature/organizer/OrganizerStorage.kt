package com.github.mytv.feature.organizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import com.github.mytv.data.files.FileSystemKind
import com.github.mytv.data.files.StorageFacts
import com.github.mytv.data.files.VolumeInfo
import com.github.mytv.data.files.VolumeKind
import java.io.File
import java.util.Locale

/**
 * Storage roots, permission state and save-target handling for the phone/PC page.
 *
 * A location is always a root id plus a relative path. Without "all files access"
 * (API 30+) or the legacy write permission (API 23-29) the shared roots are listed as
 * read only; the design's restricted mode narrows the list to the five standard
 * public directories.
 */
class OrganizerStorage(private val context: Context) : StorageFacts {
    companion object {
        const val ROOT_PRIMARY = "primary"
        const val ROOT_USB_PREFIX = "usb:"
        const val ROOT_STD_PREFIX = "std:"
        private const val PREFS = "launcher_organizer"
        private const val KEY_TARGET_ROOT = "target_root"
        private const val KEY_TARGET_PATH = "target_path"
        val STANDARD_DIRS = linkedMapOf(
            "Download" to Environment.DIRECTORY_DOWNLOADS,
            "Movies" to Environment.DIRECTORY_MOVIES,
            "Pictures" to Environment.DIRECTORY_PICTURES,
            "Music" to Environment.DIRECTORY_MUSIC,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun allFilesAccess(): Boolean? =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else null

    fun legacyStorage(): Boolean? =
        if (Build.VERSION.SDK_INT == 29) Environment.isExternalStorageLegacy() else null

    fun writePermission(): Boolean? {
        if (Build.VERSION.SDK_INT !in 23..29) return null
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isWritable(): Boolean {
        val all = allFilesAccess()
        if (all != null) return all
        return writePermission() == true
    }

    fun snapshot(restricted: Boolean): StorageState = StorageState(
        allFiles = allFilesAccess(),
        legacy = legacyStorage(),
        writePermission = writePermission(),
        roots = roots(restricted),
    )

    fun roots(restricted: Boolean): List<StorageRoot> {
        val out = ArrayList<StorageRoot>()
        val limited = restricted || (Build.VERSION.SDK_INT >= 30 && allFilesAccess() != true)
        if (limited) {
            for ((id, dir) in STANDARD_DIRS) {
                val file = Environment.getExternalStoragePublicDirectory(dir)
                out += describe(
                    id = ROOT_STD_PREFIX + id,
                    label = id,
                    file = file,
                    writable = isWritable() && ensureDirectory(file),
                    restricted = true,
                )
            }
            return out
        }

        val primary = Environment.getExternalStorageDirectory()
        if (primary != null) {
            out += describe(
                id = ROOT_PRIMARY,
                label = context.getString(R.string.organizer_internal_storage),
                file = primary,
                writable = isWritable() && primary.canWrite(),
                restricted = false,
            )
        }
        out += removableRoots()
        return out
    }

    fun resolveRoot(rootId: String, restricted: Boolean): File? {
        val limited = restricted || (Build.VERSION.SDK_INT >= 30 && allFilesAccess() != true)
        if (limited) {
            val name = rootId.removePrefix(ROOT_STD_PREFIX)
            val dir = STANDARD_DIRS[name] ?: return null
            return Environment.getExternalStoragePublicDirectory(dir)
        }
        if (rootId == ROOT_PRIMARY) return Environment.getExternalStorageDirectory()
        if (rootId.startsWith(ROOT_STD_PREFIX)) {
            val name = rootId.removePrefix(ROOT_STD_PREFIX)
            val dir = STANDARD_DIRS[name] ?: return null
            return Environment.getExternalStoragePublicDirectory(dir)
        }
        if (rootId.startsWith(ROOT_USB_PREFIX)) {
            val uuid = rootId.removePrefix(ROOT_USB_PREFIX)
            return removableVolumes().firstOrNull { it.uuid == uuid }?.directory
        }
        return null
    }

    fun rootIdFor(file: File): String? {
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && isInside(primary, file)) return ROOT_PRIMARY
        for (volume in removableVolumes()) {
            val dir = volume.directory ?: continue
            if (isInside(dir, file)) return ROOT_USB_PREFIX + (volume.uuid ?: "unknown")
        }
        return null
    }

    fun target(): SaveTarget = SaveTarget(
        root = prefs.getString(KEY_TARGET_ROOT, ROOT_PRIMARY) ?: ROOT_PRIMARY,
        path = prefs.getString(KEY_TARGET_PATH, "Download") ?: "Download",
    )

    fun setTarget(target: SaveTarget) {
        prefs.edit()
            .putString(KEY_TARGET_ROOT, target.root)
            .putString(KEY_TARGET_PATH, target.path)
            .apply()
    }

    fun targetDirectory(): File? {
        val target = target()
        val root = resolveRoot(target.root, restricted = false)
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (target.path.isBlank()) root else File(root, target.path)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    /** Intent that opens the storage grant page; null when no handler exists. */
    fun storageSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= 30) {
            val app = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            if (app.resolveActivity(context.packageManager) != null) return app
            val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (all.resolveActivity(context.packageManager) != null) return all
        }
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        return if (details.resolveActivity(context.packageManager) != null) details else null
    }

    override fun volumeOf(target: File): VolumeInfo {
        val primary = Environment.getExternalStorageDirectory()
        val removable = removableVolumes().any { volume ->
            val dir = volume.directory ?: return@any false
            isInside(dir, target)
        }
        val root = when {
            removable -> removableVolumes().firstOrNull { volume ->
                val dir = volume.directory ?: return@firstOrNull false
                isInside(dir, target)
            }?.directory ?: target.parentFile ?: target
            primary != null && isInside(primary, target) -> primary
            else -> target.parentFile ?: target
        }
        val stat = try {
            StatFs(root.absolutePath)
        } catch (_: Exception) {
            null
        }
        val total = stat?.totalBytes ?: 0L
        val free = stat?.availableBytes ?: 0L
        val fileSystem = if (removable) {
            if (total <= 32L * 1024 * 1024 * 1024) FileSystemKind.FAT32 else FileSystemKind.EXFAT
        } else {
            FileSystemKind.OTHER
        }
        return VolumeInfo(
            id = rootIdFor(root) ?: root.absolutePath,
            kind = if (removable) VolumeKind.REMOVABLE else VolumeKind.INTERNAL,
            fileSystem = fileSystem,
            totalBytes = total,
            freeBytes = free,
        )
    }

    fun volumeStats(rootId: String): Triple<Long, Long, String>? {
        val dir = resolveRoot(rootId, restricted = false) ?: return null
        val stat = try {
            StatFs(dir.absolutePath)
        } catch (_: Exception) {
            return null
        }
        val removable = rootId.startsWith(ROOT_USB_PREFIX)
        val fs = if (removable) {
            if (stat.totalBytes <= 32L * 1024 * 1024 * 1024) "vfat" else "exfat"
        } else {
            "ext4"
        }
        return Triple(stat.totalBytes, stat.availableBytes, fs)
    }

    private fun removableRoots(): List<StorageRoot> = removableVolumes().mapNotNull { volume ->
        val dir = volume.directory ?: return@mapNotNull null
        if (!dir.isDirectory) return@mapNotNull null
        val uuid = volume.uuid ?: return@mapNotNull null
        describe(
            id = ROOT_USB_PREFIX + uuid,
            label = volume.label ?: "USB",
            file = dir,
            writable = isWritable() && dir.canWrite(),
            restricted = false,
        )
    }

    private class RemovableVolume(val uuid: String?, val directory: File?, val label: String?)

    private fun removableVolumes(): List<RemovableVolume> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        if (Build.VERSION.SDK_INT < 24) return emptyList()
        val out = ArrayList<RemovableVolume>()
        for (volume in manager.storageVolumes) {
            if (!volume.isRemovable) continue
            val directory = volumeDirectory(volume)
            out += RemovableVolume(volume.uuid, directory, volume.getDescription(context))
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return volume.directory
        val uuid = volume.uuid ?: return null
        return File("/storage/$uuid")
    }

    private fun describe(
        id: String,
        label: String,
        file: File,
        writable: Boolean,
        restricted: Boolean,
    ): StorageRoot {
        val stat = try {
            StatFs(file.absolutePath)
        } catch (_: Exception) {
            null
        }
        val removable = id.startsWith(ROOT_USB_PREFIX)
        val fs = when {
            !removable -> "ext4"
            (stat?.totalBytes ?: 0) <= 32L * 1024 * 1024 * 1024 -> "vfat"
            else -> "exfat"
        }
        return StorageRoot(
            id = id,
            label = label,
            total = stat?.totalBytes ?: 0,
            free = stat?.availableBytes ?: 0,
            writable = writable,
            fileSystem = fs,
            restricted = restricted,
        )
    }

    private fun ensureDirectory(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    private fun isInside(root: File, file: File): Boolean {
        val rootPath = try {
            root.canonicalPath
        } catch (_: Exception) {
            root.absolutePath
        }.trimEnd(File.separatorChar)
        val path = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    fun fileSystemKindOf(file: File): FileSystemKind = volumeOf(file).fileSystem

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
