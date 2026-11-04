package com.github.mytv.feature.organizer

import java.io.File

/** Kinds shown in the TV transfer list and returned by `GET /api/transfers`. */
enum class TransferKind(val wire: String) {
    UPLOAD("upload"),
    DOWNLOAD("download"),
    PUSH("push"),
    OPEN("open"),
}

enum class TransferState(val wire: String) {
    QUEUED("queued"),
    ACTIVE("active"),
    DONE("done"),
    PENDING("pending"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

enum class TransferAction(val wire: String) {
    INSTALL("install"),
    VIEW("view"),
    PLAY("play"),
    NONE("none"),
}

enum class TransferResult(val wire: String) {
    INSTALLED("installed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    SHOWN("shown"),
    PLAYED("played"),
}

data class OrganizerTransfer(
    val id: String,
    val kind: TransferKind,
    val name: String,
    val bytes: Long = 0,
    val total: Long = 0,
    val state: TransferState = TransferState.QUEUED,
    val action: TransferAction = TransferAction.NONE,
    val result: TransferResult? = null,
    val error: String? = null,
    val sessionId: String? = null,
    val filePath: String? = null,
    val mime: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class OrganizerSessionView(
    val id: String,
    val device: String,
    val approved: Boolean = true,
    val activeTransfers: Int = 0,
)

data class SaveTarget(val root: String = "primary", val path: String = "Download")

data class StorageRoot(
    val id: String,
    val label: String,
    val total: Long,
    val free: Long,
    val writable: Boolean,
    val fileSystem: String,
    val restricted: Boolean = false,
)

data class StorageState(
    val allFiles: Boolean? = null,
    val legacy: Boolean? = null,
    val writePermission: Boolean? = null,
    val roots: List<StorageRoot> = emptyList(),
)

data class OrganizerState(
    val running: Boolean = false,
    val address: String? = null,
    val port: Int = 0,
    val token: String = "",
    val code: String = "",
    val networkType: String = "none",
    val sessions: List<OrganizerSessionView> = emptyList(),
    val transfers: List<OrganizerTransfer> = emptyList(),
    val target: SaveTarget = SaveTarget(),
    val storage: StorageState = StorageState(),
    val pageVisible: Boolean = false,
    val acceptingNewSessions: Boolean = true,
    val pendingConnection: PendingConnection? = null,
    val install: InstallCardState = InstallCardState(),
) {
    val qrContent: String?
        get() = if (running && address != null && port > 0 && token.isNotEmpty()) {
            "http://$address:$port/#t=$token"
        } else {
            null
        }
}

/** CONTRACT `install` object, also used by the install card. */
data class InstallCardState(
    val state: String = "idle",
    val packageName: String? = null,
    val versionCode: Long = 0,
    val error: String? = null,
    val apkPath: String? = null,
)

data class PreparedInstall(
    val files: List<File>,
    val packageName: String,
    val versionCode: Long,
    val source: File,
    val obbFiles: List<Pair<File, String>> = emptyList(),
)

/** Files and directories are always addressed as a root id plus a relative path. */
data class FileLocation(val root: String, val path: String)
