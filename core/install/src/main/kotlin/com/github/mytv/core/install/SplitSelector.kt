package com.github.mytv.core.install

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Precheck failures shown on the install card (§14). */
enum class InstallError {
    SIGNATURE,
    DOWNGRADE,
    MIN_SDK,
    ABI,
    UNREADABLE,
}

/** The role a single APK plays inside an app bundle. */
enum class SplitKind { BASE, FEATURE, ABI, DENSITY, LANGUAGE }

/**
 * Metadata for one APK of a split install. Production obtains package name, version
 * code, signature and minSdk through [ApkMetadataReader] (PackageManager with
 * `GET_SIGNING_CERTIFICATES` on API 28+); unit tests build the values directly.
 */
data class SplitApk(
    val file: File,
    val splitName: String?,
    val kind: SplitKind,
    val packageName: String,
    val versionCode: Long,
    val signatureSha256: String,
    val minSdk: Int? = null,
    val abi: String? = null,
    val densityDpi: Int? = null,
    val language: String? = null,
    val bundledAbis: List<String> = emptyList(),
)

/** The currently installed package, when one exists. */
data class InstalledApp(
    val packageName: String,
    val versionCode: Long,
    val signatureSha256: String,
)

/** Device values used for split selection. Injected so tests never touch android.os.Build. */
interface DeviceFacts {
    val supportedAbis: List<String>
    val densityDpi: Int
    val languages: List<String>
    val sdkInt: Int
}

data class SimpleDeviceFacts(
    override val supportedAbis: List<String>,
    override val densityDpi: Int,
    override val languages: List<String>,
    override val sdkInt: Int,
) : DeviceFacts

/** Reads zip entry names and small text entries (`.xapk` manifest, `lib/<abi>/`). */
interface ArchiveReader {
    fun entries(archive: File): List<String>
    fun readText(archive: File, entry: String): String?
}

class ZipArchiveReader : ArchiveReader {
    override fun entries(archive: File): List<String> = ZipFile(archive).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
    }

    override fun readText(archive: File, entry: String): String? = ZipFile(archive).use { zip ->
        val item = zip.getEntry(entry) ?: return@use null
        zip.getInputStream(item).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

/** Reads one APK's package metadata. Production implementations use PackageManager. */
interface ApkMetadataReader {
    fun read(apk: File, splitName: String?): SplitApk?
}

/** An APK file paired with the split name reported by the bundle container. */
data class SplitEntry(val apk: File, val splitName: String?)

sealed class SelectionResult {
    data class Selected(
        val base: SplitApk,
        val features: List<SplitApk>,
        val abi: SplitApk?,
        val density: SplitApk?,
        val language: SplitApk?,
    ) : SelectionResult() {
        val splits: List<SplitApk>
            get() = listOf(base) + features + listOfNotNull(abi, density, language)

        val files: List<File>
            get() = splits.map { it.file }
    }

    data class Blocked(val error: InstallError, val message: String) : SelectionResult()
}

data class XapkApk(val fileName: String, val splitName: String?)
data class XapkObb(val fileName: String, val installPath: String)

data class XapkManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val apks: List<XapkApk>,
    val obbs: List<XapkObb>,
)

/**
 * Chooses the splits to hand to PackageInstaller (§14, "分包选择"):
 *  - base and every feature split are mandatory;
 *  - the ABI split follows the device's SUPPORTED_ABIS priority and must exist;
 *  - the density split is the nearest value at or above the device, else the highest;
 *  - the language split follows the system language list and is simply skipped when
 *    nothing matches;
 *  - splits that disagree on package, versionCode or signature are unreadable.
 *
 * Everything the algorithm depends on is injected: [DeviceFacts], [ArchiveReader] and
 * [ApkMetadataReader]. The core logic therefore runs as a plain JVM unit test.
 */
class SplitSelector(
    private val device: DeviceFacts,
    private val archives: ArchiveReader = ZipArchiveReader(),
    private val metadata: ApkMetadataReader? = null,
) {
    companion object {
        val ABI_NAMES: Set<String> = setOf(
            "armeabi",
            "armeabi-v7a",
            "arm64-v8a",
            "x86",
            "x86_64",
            "mips",
            "mips64",
            "riscv64",
        )

        val DENSITIES: Map<String, Int> = mapOf(
            "ldpi" to 120,
            "mdpi" to 160,
            "tvdpi" to 213,
            "hdpi" to 240,
            "xhdpi" to 320,
            "xxhdpi" to 480,
            "xxxhdpi" to 640,
        )

        fun kindOfSplit(splitName: String?): SplitKind {
            if (splitName.isNullOrBlank() || splitName.equals("base", ignoreCase = true)) {
                return SplitKind.BASE
            }
            val tail = splitName.substringAfterLast('.')
            if (abiOfSplitName(tail) != null) return SplitKind.ABI
            if (DENSITIES.containsKey(tail.lowercase(Locale.ROOT))) return SplitKind.DENSITY
            if (splitName.split('.').any { it.equals("config", ignoreCase = true) }) {
                return SplitKind.LANGUAGE
            }
            return SplitKind.FEATURE
        }

        fun abiOfSplit(splitName: String?): String? {
            if (splitName.isNullOrBlank()) return null
            return abiOfSplitName(splitName.substringAfterLast('.'))
        }

        fun densityOfSplit(splitName: String?): Int? {
            if (splitName.isNullOrBlank()) return null
            return DENSITIES[splitName.substringAfterLast('.').lowercase(Locale.ROOT)]
        }

        fun languageOfSplit(splitName: String?): String? {
            if (splitName.isNullOrBlank()) return null
            if (kindOfSplit(splitName) != SplitKind.LANGUAGE) return null
            return splitName.substringAfterLast('.')
        }

        private fun abiOfSplitName(token: String): String? {
            if (ABI_NAMES.contains(token)) return token
            val hyphenated = token.replace('_', '-')
            return if (ABI_NAMES.contains(hyphenated)) hyphenated else null
        }
    }

    fun select(splits: List<SplitApk>, installed: InstalledApp? = null): SelectionResult {
        val bases = splits.filter { it.kind == SplitKind.BASE }
        if (bases.size != 1) {
            return SelectionResult.Blocked(InstallError.UNREADABLE, "exactly one base split is required")
        }
        val base = bases[0]
        if (splits.any { it.packageName != base.packageName }) {
            return SelectionResult.Blocked(InstallError.UNREADABLE, "package names differ between splits")
        }
        if (splits.any { it.versionCode != base.versionCode }) {
            return SelectionResult.Blocked(InstallError.UNREADABLE, "version codes differ between splits")
        }
        if (splits.any { it.signatureSha256 != base.signatureSha256 }) {
            return SelectionResult.Blocked(InstallError.UNREADABLE, "signatures differ between splits")
        }
        for (split in splits) {
            when (split.kind) {
                SplitKind.ABI -> if (split.abi.isNullOrBlank()) {
                    return SelectionResult.Blocked(InstallError.UNREADABLE, "ABI split without an ABI")
                }
                SplitKind.DENSITY -> if ((split.densityDpi ?: 0) <= 0) {
                    return SelectionResult.Blocked(InstallError.UNREADABLE, "density split without a density")
                }
                SplitKind.LANGUAGE -> if (split.language.isNullOrBlank()) {
                    return SelectionResult.Blocked(InstallError.UNREADABLE, "language split without a language")
                }
                else -> Unit
            }
        }

        if (installed != null && installed.packageName == base.packageName) {
            if (!installed.signatureSha256.equals(base.signatureSha256, ignoreCase = true)) {
                return SelectionResult.Blocked(InstallError.SIGNATURE, "signature differs from installed app")
            }
            if (base.versionCode < installed.versionCode) {
                return SelectionResult.Blocked(InstallError.DOWNGRADE, "version is lower than installed app")
            }
        }

        val requiredSdk = splits.mapNotNull { it.minSdk }.maxOrNull()
        if (requiredSdk != null && device.sdkInt < requiredSdk) {
            return SelectionResult.Blocked(InstallError.MIN_SDK, "device SDK is too low")
        }

        val abiSplits = splits.filter { it.kind == SplitKind.ABI }
        val selectedAbi = device.supportedAbis.firstNotNullOfOrNull { preferred ->
            abiSplits.firstOrNull { it.abi?.equals(preferred, ignoreCase = true) == true }
        }
        if (abiSplits.isNotEmpty() && selectedAbi == null) {
            return SelectionResult.Blocked(InstallError.ABI, "no ABI matches the device")
        }
        if (abiSplits.isEmpty()) {
            val bundled = splits.flatMap { it.bundledAbis }.distinct()
            if (bundled.isNotEmpty() &&
                bundled.none { abi -> device.supportedAbis.any { it.equals(abi, ignoreCase = true) } }
            ) {
                return SelectionResult.Blocked(InstallError.ABI, "no ABI matches the device")
            }
        }

        val densitySplits = splits.filter { it.kind == SplitKind.DENSITY }
        val density = densitySplits
            .filter { (it.densityDpi ?: 0) >= device.densityDpi }
            .minByOrNull { it.densityDpi ?: Int.MAX_VALUE }
            ?: densitySplits.maxByOrNull { it.densityDpi ?: 0 }

        val languageSplits = splits.filter { it.kind == SplitKind.LANGUAGE }
        val language = device.languages.asSequence()
            .mapNotNull { tag -> languageSplits.firstOrNull { languageMatches(tag, it.language) } }
            .firstOrNull()

        return SelectionResult.Selected(
            base = base,
            features = splits.filter { it.kind == SplitKind.FEATURE },
            abi = selectedAbi,
            density = density,
            language = language,
        )
    }

    /** Reads the metadata of every entry through [ApkMetadataReader]. */
    fun readSplits(entries: List<SplitEntry>): List<SplitApk>? {
        val reader = metadata ?: return null
        val result = ArrayList<SplitApk>(entries.size)
        for (entry in entries) {
            result += reader.read(entry.apk, entry.splitName) ?: return null
        }
        return result
    }

    /** All `lib/<abi>/` directories inside an APK. */
    fun nativeAbisIn(apk: File): Set<String> = archives.entries(apk)
        .mapNotNull { entry ->
            if (!entry.startsWith("lib/")) return@mapNotNull null
            entry.removePrefix("lib/").substringBefore('/').takeIf { it.isNotEmpty() }
        }
        .toSet()

    /** APK entries of a `.apks` / `.apkm` / `.xapk` container. */
    fun apkEntriesIn(archive: File): List<String> =
        archives.entries(archive).filter { it.endsWith(".apk", ignoreCase = true) }

    /** Parses the `manifest.json` of a `.xapk` archive. */
    fun parseXapk(archive: File): XapkManifest? {
        val text = archives.readText(archive, "manifest.json") ?: return null
        return parseXapkManifest(text)
    }

    fun parseXapkManifest(text: String): XapkManifest? {
        val root = try {
            MiniJson.parse(text) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return null

        val packageName = root["package_name"] as? String
        if (packageName.isNullOrBlank() || !isSafeArchiveName(packageName)) return null
        val versionCode = numberValue(root["version_code"])?.toLong() ?: return null
        val versionName = root["version_name"] as? String
        val minSdk = numberValue(root["min_sdk_version"])?.toInt()

        val apkList = (root["split_apks"] as? List<*>) ?: (root["apks"] as? List<*>)
        val apks = ArrayList<XapkApk>()
        if (apkList != null) {
            for (item in apkList) {
                val map = item as? Map<*, *> ?: return null
                val file = map["file"] as? String ?: return null
                if (!isSafeArchiveName(file)) return null
                val id = (map["id"] as? String)?.takeIf { it.isNotBlank() }
                apks += XapkApk(file, id)
            }
        }

        val expansions = root["expansions"] as? List<*>
        val obbs = ArrayList<XapkObb>()
        if (expansions != null) {
            for (item in expansions) {
                val map = item as? Map<*, *> ?: return null
                val file = map["file"] as? String ?: return null
                if (!isSafeArchiveName(file)) return null
                val installPath = map["install_path"] as? String
                val target = obbPath(packageName, file, installPath) ?: return null
                obbs += XapkObb(file, target)
            }
        }

        return XapkManifest(packageName, versionCode, versionName, minSdk, apks, obbs)
    }

    private fun languageMatches(deviceTag: String, splitLanguage: String?): Boolean {
        if (splitLanguage.isNullOrBlank()) return false
        if (deviceTag.equals(splitLanguage, ignoreCase = true)) return true
        val devicePrimary = deviceTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        val splitPrimary = splitLanguage.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        return devicePrimary == splitPrimary
    }

    private fun obbPath(packageName: String, fileName: String, installPath: String?): String? {
        val packageDir = "Android/obb/$packageName"
        val requested = installPath?.trim()?.replace('\\', '/')?.trim('/')
        val dir = if (requested.isNullOrEmpty()) packageDir else requested
        if (dir.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        if (dir != packageDir && !dir.startsWith("$packageDir/")) return null
        return if (dir.endsWith("/$fileName")) dir else "$dir/$fileName"
    }

    private fun isSafeArchiveName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    private fun numberValue(value: Any?): Number? = when (value) {
        is Number -> value
        is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()
        else -> null
    }
}
