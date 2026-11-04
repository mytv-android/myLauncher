package com.github.mytv.feature.organizer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.github.mytv.core.install.ApkMetadataReader
import com.github.mytv.core.install.DeviceFacts
import com.github.mytv.core.install.InstallError
import com.github.mytv.core.install.InstalledApp
import com.github.mytv.core.install.SelectionResult
import com.github.mytv.core.install.SplitApk
import com.github.mytv.core.install.SplitEntry
import com.github.mytv.core.install.SplitKind
import com.github.mytv.core.install.SplitSelector
import com.github.mytv.core.install.XapkManifest
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Package install pipeline for pushed APKs and split bundles.
 *
 * The container is unpacked to the cache directory, every APK is classified and the
 * actual split choice is made by [SplitSelector]. Prechecks (signature, downgrade,
 * minSdk, ABI, unreadable bundle) happen before a session is created. Committing always
 * goes through the system confirmation UI: API 31+ sets
 * `USER_ACTION_REQUIRED`, and `STATUS_PENDING_USER_ACTION` is launched from the
 * foreground.
 */
class InstallManager(private val context: Context) {
    companion object {
        private const val RESULT_ACTION = "com.github.mytv.feature.organizer.INSTALL_RESULT"
        private const val UNIQUE_INTENT_BASE = 0x4d4c41
        val ARCHIVE_SUFFIXES = setOf("apks", "apkm", "xapk")
    }

    private val _state = MutableStateFlow(InstallCardState())
    val state: StateFlow<InstallCardState> = _state.asStateFlow()

    private var prepared: PreparedInstall? = null
    private var deleteSourceOnSuccess = false
    private var receiver: BroadcastReceiver? = null
    private val device = object : DeviceFacts {
        override val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
        override val densityDpi: Int = context.resources.displayMetrics.densityDpi
        override val languages: List<String> = Locale.getDefault().let {
            listOf(it.toLanguageTag(), it.language)
        }
        override val sdkInt: Int = Build.VERSION.SDK_INT
    }
    private val selector = SplitSelector(device, metadata = PmMetadataReader())

    fun canRequestInstalls(): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return context.packageManager.canRequestPackageInstalls()
        return try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) != 0
        } catch (_: Exception) {
            false
        }
    }

    fun unknownSourcesIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        val security = Intent(Settings.ACTION_SECURITY_SETTINGS)
        return if (security.resolveActivity(context.packageManager) != null) security else null
    }

    /** Validates [file] and exposes an install card in ready / blocked state. */
    fun prepare(file: File, deleteOnSuccess: Boolean = false): InstallCardState {
        releasePrepared()
        deleteSourceOnSuccess = deleteOnSuccess
        if (!file.exists() || !file.isFile) return fail("unreadable")
        val suffix = file.extension.lowercase(Locale.ROOT)
        val result = try {
            when {
                suffix == "apk" -> prepareApk(file)
                suffix in ARCHIVE_SUFFIXES -> prepareArchive(file)
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return fail("unreadable")

        return when (result) {
            is PreparedResult.Blocked -> fail(result.error)
            is PreparedResult.Ready -> {
                prepared = result.install
                val state = InstallCardState(
                    state = if (canRequestInstalls()) "ready" else "blocked",
                    packageName = result.install.packageName,
                    versionCode = result.install.versionCode,
                    error = if (canRequestInstalls()) null else "unknown_sources",
                    apkPath = file.absolutePath,
                )
                _state.value = state
                state
            }
        }
    }

    /** Opens the system confirmation path; must be called from the foreground. */
    fun install(): Boolean {
        val install = prepared ?: return false
        if (!canRequestInstalls()) {
            _state.value = _state.value.copy(state = "blocked", error = "unknown_sources")
            return false
        }
        return try {
            installObbs(install)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            if (Build.VERSION.SDK_INT >= 26) {
                params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                for ((index, apk) in install.files.withIndex()) {
                    val name = apk.name.ifBlank { "split-$index.apk" }
                    session.openWrite(name, 0, apk.length()).use { out ->
                        apk.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                }
                val pending = resultPendingIntent()
                session.commit(pending.intentSender)
            }
            registerResultReceiver()
            _state.value = _state.value.copy(state = "installing", error = null)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(state = "failed", error = "unreadable")
            false
        }
    }

    fun cancel() {
        if (_state.value.state == "ready" || _state.value.state == "blocked" ||
            _state.value.state == "pending_user"
        ) {
            _state.value = _state.value.copy(state = "cancelled")
        }
        releasePrepared()
    }

    fun openInstalled() {
        val packageName = _state.value.packageName ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun clear() {
        _state.value = InstallCardState()
        releasePrepared()
    }

    fun errorText(error: String?): String = when (error) {
        "signature" -> context.getString(R.string.organizer_signature_diff)
        "downgrade" -> context.getString(R.string.organizer_version_low)
        "min_sdk" -> context.getString(R.string.organizer_abi_unsupported)
        "abi" -> context.getString(R.string.organizer_abi_unsupported)
        "unknown_sources" -> context.getString(R.string.organizer_unknown_sources)
        else -> context.getString(R.string.organizer_unreadable)
    }

    private sealed class PreparedResult {
        data class Ready(val install: PreparedInstall) : PreparedResult()
        data class Blocked(val error: String) : PreparedResult()
    }

    private fun prepareApk(file: File): PreparedResult? {
        val info = archiveInfo(file) ?: return PreparedResult.Blocked("unreadable")
        val split = splitOf(file, null, info) ?: return PreparedResult.Blocked("unreadable")
        val installed = installedApp(info.packageName)
        return when (val selection = selector.select(listOf(split), installed)) {
            is SelectionResult.Blocked -> PreparedResult.Blocked(errorName(selection.error))
            is SelectionResult.Selected -> PreparedResult.Ready(
                PreparedInstall(
                    files = selection.files,
                    packageName = selection.base.packageName,
                    versionCode = selection.base.versionCode,
                    source = file,
                ),
            )
        }
    }

    private fun prepareArchive(file: File): PreparedResult? {
        val suffix = file.extension.lowercase(Locale.ROOT)
        val extractDir = File(
            context.cacheDir,
            "organizer_install/${UUID.randomUUID()}",
        ).apply { mkdirs() }
        val entries = try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .map { it.name }
                    .toList()
            }
        } catch (_: Exception) {
            return PreparedResult.Blocked("unreadable")
        }
        if (entries.isEmpty()) return PreparedResult.Blocked("unreadable")

        val manifest: XapkManifest? = if (suffix == "xapk") selector.parseXapk(file) else null
        val wanted = manifest?.apks?.map { it.fileName }?.toSet() ?: entries.toSet()
        val splitEntries = ArrayList<SplitEntry>()
        try {
            ZipFile(file).use { zip ->
                for (entryName in entries) {
                    if (entryName !in wanted && manifest != null) continue
                    val out = File(extractDir, entryName.replace('/', '_'))
                    zip.getInputStream(zip.getEntry(entryName)).use { input ->
                        out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                    val id = manifest?.apks?.firstOrNull { it.fileName == entryName }?.splitName
                        ?: splitNameOf(entryName)
                    splitEntries += SplitEntry(out, id)
                }
            }
        } catch (_: Exception) {
            return PreparedResult.Blocked("unreadable")
        }
        val splits = selector.readSplits(splitEntries) ?: return PreparedResult.Blocked("unreadable")
        val base = splits.firstOrNull { it.kind == SplitKind.BASE }
            ?: return PreparedResult.Blocked("unreadable")
        val installed = installedApp(base.packageName)
        return when (val selection = selector.select(splits, installed)) {
            is SelectionResult.Blocked -> PreparedResult.Blocked(errorName(selection.error))
            is SelectionResult.Selected -> PreparedResult.Ready(
                PreparedInstall(
                    files = selection.files,
                    packageName = selection.base.packageName,
                    versionCode = selection.base.versionCode,
                    source = file,
                    obbFiles = extractObbs(file, manifest, extractDir),
                ),
            )
        }
    }

    private fun extractObbs(
        archive: File,
        manifest: XapkManifest?,
        extractDir: File,
    ): List<Pair<File, String>> {
        val obbs = manifest?.obbs ?: return emptyList()
        val out = ArrayList<Pair<File, String>>()
        try {
            ZipFile(archive).use { zip ->
                for (obb in obbs) {
                    val entry = zip.getEntry(obb.fileName) ?: continue
                    val file = File(extractDir, obb.fileName.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                    out += file to obb.installPath
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun installObbs(install: PreparedInstall) {
        for ((file, path) in install.obbFiles) {
            try {
                val target = File(path)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            } catch (_: Exception) {
            }
        }
    }

    private fun splitNameOf(entryName: String): String {
        val name = entryName.substringAfterLast('/').removeSuffix(".apk")
        if (name.equals("base", ignoreCase = true)) return "base"
        if (name.startsWith("base-master", ignoreCase = true)) return "base"
        if (name.startsWith("base_", ignoreCase = true)) return "base"
        if (name.startsWith("base-", ignoreCase = true)) {
            // Bundletool naming: base-arm64_v8a.apk / base-xxhdpi.apk / base-en.apk.
            return "config." + name.substringAfter("base-")
        }
        return name
    }

    private fun splitOf(file: File, splitName: String?, info: ArchiveInfo): SplitApk? {
        val kind = SplitSelector.kindOfSplit(splitName)
        val abi = SplitSelector.abiOfSplit(splitName)
        val density = SplitSelector.densityOfSplit(splitName)
        val language = SplitSelector.languageOfSplit(splitName)
        return SplitApk(
            file = file,
            splitName = splitName,
            kind = kind,
            packageName = info.packageName,
            versionCode = info.versionCode,
            signatureSha256 = info.signatureSha256,
            minSdk = info.minSdk,
            abi = abi,
            densityDpi = density,
            language = language,
            bundledAbis = selector.nativeAbisIn(file).toList(),
        )
    }

    private inner class PmMetadataReader : ApkMetadataReader {
        override fun read(apk: File, splitName: String?): SplitApk? {
            val info = archiveInfo(apk) ?: return null
            return splitOf(apk, splitName, info)
        }
    }

    private data class ArchiveInfo(
        val packageName: String,
        val versionCode: Long,
        val signatureSha256: String,
        val minSdk: Int?,
    )

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): ArchiveInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (_: Exception) {
            null
        } ?: return null
        val signature = signatureOf(info) ?: return null
        val version = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val minSdk = if (Build.VERSION.SDK_INT >= 24) info.applicationInfo?.minSdkVersion else null
        return ArchiveInfo(info.packageName, version, signature, minSdk)
    }

    @Suppress("DEPRECATION")
    private fun signatureOf(info: android.content.pm.PackageInfo): String? {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) builder.append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        return builder.toString()
    }

    @Suppress("DEPRECATION")
    private fun installedApp(packageName: String): InstalledApp? {
        val info = try {
            context.packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
            )
        } catch (_: Exception) {
            null
        } ?: return null
        val version = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return InstalledApp(packageName, version, signatureOf(info) ?: "")
    }

    private fun errorName(error: InstallError): String = when (error) {
        InstallError.SIGNATURE -> "signature"
        InstallError.DOWNGRADE -> "downgrade"
        InstallError.MIN_SDK -> "min_sdk"
        InstallError.ABI -> "abi"
        InstallError.UNREADABLE -> "unreadable"
    }

    private fun fail(error: String): InstallCardState {
        val state = InstallCardState(state = "failed", error = error)
        _state.value = state
        return state
    }

    private class PendingResult(val intentSender: android.content.IntentSender)

    private fun resultPendingIntent(): PendingResult {
        val intent = Intent(RESULT_ACTION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            UNIQUE_INTENT_BASE + UUID.randomUUID().hashCode(),
            intent,
            flags,
        )
        return PendingResult(pending.intentSender)
    }

    private fun registerResultReceiver() {
        unregisterResultReceiver()
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != RESULT_ACTION) return
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        _state.value = _state.value.copy(state = "pending_user")
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(confirm)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        _state.value = _state.value.copy(state = "installed", error = null)
                        finishPrepared()
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        _state.value = _state.value.copy(state = "cancelled")
                        unregisterResultReceiver()
                    }
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        _state.value = _state.value.copy(
                            state = "failed",
                            error = mapStatus(status, message),
                        )
                        unregisterResultReceiver()
                    }
                }
            }
        }
        receiver = resultReceiver
        val filter = IntentFilter(RESULT_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(resultReceiver, filter)
        }
    }

    private fun mapStatus(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "signature"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "abi"
        PackageInstaller.STATUS_FAILURE_INVALID -> "unreadable"
        else -> if ((message ?: "").contains("version", ignoreCase = true)) "downgrade" else "unreadable"
    }

    private fun unregisterResultReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
    }

    private fun releasePrepared() {
        prepared = null
        unregisterResultReceiver()
    }

    private fun finishPrepared() {
        unregisterResultReceiver()
        val install = prepared
        if (deleteSourceOnSuccess && install != null) {
            install.source.delete()
        }
        prepared = null
        val dir = File(context.cacheDir, "organizer_install")
        dir.listFiles()?.forEach { child -> child.deleteRecursively() }
    }
}
