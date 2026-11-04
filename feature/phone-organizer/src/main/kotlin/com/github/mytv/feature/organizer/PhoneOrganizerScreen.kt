package com.github.mytv.feature.organizer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.LauncherWavyProgress
import com.github.mytv.core.designsystem.LocalLauncherColors
import com.github.mytv.core.tvfocus.TvFocusable
import java.util.Locale

/**
 * The full "手机与电脑" page.
 *
 * Tags follow CONTRACT §2: `organizer`, `organizer:qr`, `organizer:address`,
 * `organizer:code`, `organizer:device:<id>`, `transfer:<id>`, `organizer:target`,
 * `organizer:storage`, and the `menu` panel with `menu:open`, `menu:delete`,
 * `menu:cancel_transfer`, `menu:remove_transfer`, `menu:disconnect`.
 *
 * The launcher owns page state; this composable only renders [state] and forwards
 * actions. [onLoadDirectories] lets the save-location drawer list subfolders.
 */
@Composable
fun PhoneOrganizerScreen(
    state: OrganizerState,
    onDisconnectSession: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onRemoveTransfer: (String) -> Unit,
    onDeleteTransfer: (String) -> Unit,
    onOpenTransfer: (String) -> Unit,
    onSetTarget: (String, String) -> Unit,
    onStorageSettings: () -> Unit,
    onRefreshStorage: () -> Unit = {},
    onLoadDirectories: suspend (root: String, path: String) -> List<String> = { _, _ -> emptyList() },
    modifier: Modifier = Modifier,
) {
    val colors = LocalLauncherColors.current.value
    var menuTransferId by remember { mutableStateOf<String?>(null) }
    var menuSessionId by remember { mutableStateOf<String?>(null) }
    var targetOpen by remember { mutableStateOf(false) }
    var pickerRoot by remember { mutableStateOf(state.target.root) }
    var pickerPath by remember { mutableStateOf(state.target.path) }
    var directories by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(pickerRoot, pickerPath, targetOpen) {
        if (targetOpen) directories = onLoadDirectories(pickerRoot, pickerPath)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("organizer")
            .padding(start = 48.dp, end = 48.dp, top = 92.dp, bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(
                modifier = Modifier.width(360.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.organizer_title),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                ConnectionCard(state, colors)
                Spacer(Modifier.height(8.dp))
                StorageRows(state, colors, onStorageSettings, onRefreshStorage) {
                    pickerRoot = state.target.root
                    pickerPath = state.target.path
                    targetOpen = true
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.organizer_devices),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                if (state.sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.organizer_no_transfers),
                        color = colors.onSurface.copy(alpha = 0.55f),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (session in state.sessions) {
                            TvFocusable(
                                tag = "organizer:device:${session.id}",
                                rowId = "devices",
                                onClick = { menuSessionId = session.id },
                                shape = RoundedCornerShape(20.dp),
                            ) { focused ->
                                Row(
                                    modifier = Modifier
                                        .background(
                                            if (focused) LauncherSurfaceContainerHigh else LauncherCard,
                                            RoundedCornerShape(20.dp),
                                        )
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.organizer_devices),
                                        color = colors.onSurface,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = if (session.activeTransfers > 0) {
                                            stringResource(R.string.organizer_transferring)
                                        } else {
                                            stringResource(R.string.organizer_idle)
                                        },
                                        color = colors.primary,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.organizer_transfers),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                if (state.transfers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.organizer_no_transfers),
                        color = colors.onSurface.copy(alpha = 0.55f),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val sorted = state.transfers.sortedByDescending { it.createdAt }
                        for ((index, transfer) in sorted.withIndex()) {
                            TransferRow(
                                transfer = transfer,
                                index = index,
                                colors = colors,
                                onClick = { menuTransferId = transfer.id },
                            )
                        }
                    }
                }
            }
        }

        val menuTransfer = menuTransferId?.let { id -> state.transfers.firstOrNull { it.id == id } }
        if (menuTransfer != null) {
            TransferMenu(
                transfer = menuTransfer,
                modifier = Modifier.align(Alignment.Center),
                onCancel = {
                    onCancelTransfer(menuTransfer.id)
                    menuTransferId = null
                },
                onRemove = {
                    onRemoveTransfer(menuTransfer.id)
                    menuTransferId = null
                },
                onDelete = {
                    onDeleteTransfer(menuTransfer.id)
                    menuTransferId = null
                },
                onOpen = {
                    onOpenTransfer(menuTransfer.id)
                    menuTransferId = null
                },
                onDismiss = { menuTransferId = null },
            )
        }
        val menuSession = menuSessionId
        if (menuSession != null) {
            SessionMenu(
                modifier = Modifier.align(Alignment.Center),
                onDisconnect = {
                    onDisconnectSession(menuSession)
                    menuSessionId = null
                },
                onDismiss = { menuSessionId = null },
            )
        }
        if (targetOpen) {
            TargetPicker(
                modifier = Modifier.align(Alignment.Center),
                roots = state.storage.roots,
                root = pickerRoot,
                path = pickerPath,
                directories = directories,
                onRoot = {
                    pickerRoot = it
                    pickerPath = ""
                    directories = emptyList()
                },
                onEnter = { pickerPath = if (pickerPath.isEmpty()) it else "$pickerPath/$it" },
                onUp = { pickerPath = pickerPath.substringBeforeLast('/', "") },
                onSelect = {
                    onSetTarget(pickerRoot, pickerPath)
                    targetOpen = false
                },
                onDismiss = { targetOpen = false },
            )
        }
    }
}

@Composable
private fun ConnectionCard(state: OrganizerState, colors: LauncherColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LauncherCard, RoundedCornerShape(28.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.running) {
            Text(
                text = stringResource(R.string.organizer_not_connected),
                color = colors.onSurface,
                fontSize = 20.sp,
            )
            return@Row
        }
        val qr = remember(state.qrContent) {
            state.qrContent?.let { QrCodes.bitmap(it, 512) }
        }
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(150.dp).testTag("organizer:qr"),
            )
        } else {
            Spacer(Modifier.size(150.dp).testTag("organizer:qr"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${state.address ?: ""}:${state.port}",
                modifier = Modifier.testTag("organizer:address"),
                color = colors.onSurface,
                fontSize = 17.sp,
            )
            Text(
                text = stringResource(R.string.organizer_pairing_code),
                color = colors.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Text(
                text = state.code.chunked(3).joinToString(" "),
                modifier = Modifier.testTag("organizer:code"),
                color = colors.primary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (state.networkType) {
                    "ethernet" -> stringResource(R.string.organizer_network_ethernet)
                    "wifi" -> stringResource(R.string.organizer_network_wifi)
                    else -> stringResource(R.string.organizer_not_connected)
                },
                color = colors.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StorageRows(
    state: OrganizerState,
    colors: LauncherColors,
    onStorageSettings: () -> Unit,
    onRefreshStorage: () -> Unit,
    onOpenTarget: () -> Unit,
) {
    val allFiles = state.storage.allFiles
    val storageLabel = if (allFiles != null) {
        stringResource(R.string.organizer_all_files)
    } else {
        stringResource(R.string.organizer_storage)
    }
    val granted = allFiles ?: state.storage.writePermission ?: false
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvFocusable(
            tag = "organizer:target",
            rowId = "settings",
            onClick = onOpenTarget,
            shape = RoundedCornerShape(20.dp),
        ) { focused ->
            SettingPill(
                label = stringResource(R.string.organizer_save_location),
                value = "",
                focused = focused,
                colors = colors,
            )
        }
        TvFocusable(
            tag = "organizer:storage",
            rowId = "settings",
            onClick = {
                onRefreshStorage()
                onStorageSettings()
            },
            shape = RoundedCornerShape(20.dp),
        ) { focused ->
            SettingPill(
                label = storageLabel,
                value = if (granted) {
                    stringResource(R.string.organizer_granted)
                } else {
                    stringResource(R.string.organizer_not_granted)
                },
                focused = focused,
                colors = colors,
            )
        }
    }
}

@Composable
private fun SettingPill(
    label: String,
    value: String,
    focused: Boolean,
    colors: LauncherColors,
) {
    Column(
        modifier = Modifier
            .background(
                if (focused) LauncherSurfaceContainerHigh else LauncherCard,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text = label, color = colors.onSurface, fontWeight = FontWeight.Medium)
        if (value.isNotEmpty()) {
            Text(text = value, color = colors.primary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TransferRow(
    transfer: OrganizerTransfer,
    index: Int,
    colors: LauncherColors,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = "transfer:${transfer.id}",
        rowId = "transfers",
        index = index,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
    ) { focused ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (focused) LauncherSurfaceContainerHigh else LauncherCard,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = transfer.name,
                    modifier = Modifier.weight(1f),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = transferStatus(transfer),
                    color = if (transfer.state == TransferState.FAILED) {
                        colors.onSurface.copy(alpha = 0.6f)
                    } else {
                        colors.primary
                    },
                    fontSize = 13.sp,
                )
            }
            if (transfer.state == TransferState.ACTIVE || transfer.state == TransferState.QUEUED) {
                val fraction = if (transfer.total > 0) {
                    (transfer.bytes.toFloat() / transfer.total.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LauncherWavyProgress(
                    progress = fraction,
                    color = colors.primary,
                    trackColor = colors.outline.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun transferStatus(transfer: OrganizerTransfer): String = when (transfer.state) {
    TransferState.QUEUED -> stringResource(R.string.organizer_waiting)
    TransferState.ACTIVE -> stringResource(R.string.organizer_transferring)
    TransferState.PENDING -> stringResource(R.string.organizer_pending)
    TransferState.FAILED -> stringResource(R.string.organizer_failed)
    TransferState.CANCELLED -> stringResource(R.string.organizer_cancelled)
    TransferState.DONE -> when (transfer.result) {
        TransferResult.INSTALLED -> stringResource(R.string.organizer_installed)
        TransferResult.SHOWN -> stringResource(R.string.organizer_showing)
        TransferResult.PLAYED -> stringResource(R.string.organizer_playing)
        else -> stringResource(R.string.organizer_done)
    }
}

@Composable
private fun TransferMenu(
    transfer: OrganizerTransfer,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalLauncherColors.current.value
    Column(
        modifier = modifier
            .width(320.dp)
            .background(LauncherSurfaceContainerHigh, RoundedCornerShape(24.dp))
            .padding(12.dp)
            .testTag("menu"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            transfer.state == TransferState.QUEUED || transfer.state == TransferState.ACTIVE -> {
                MenuEntry("menu:cancel_transfer", stringResource(R.string.organizer_cancel_transfer), onCancel)
            }
            transfer.state == TransferState.PENDING -> {
                MenuEntry("menu:open", openLabel(transfer.action), onOpen)
                MenuEntry("menu:delete", stringResource(R.string.organizer_delete), onDelete)
            }
            transfer.state == TransferState.DONE && transfer.result == TransferResult.INSTALLED -> {
                MenuEntry("menu:remove_transfer", stringResource(R.string.organizer_remove_transfer), onRemove)
            }
            transfer.state == TransferState.DONE && transfer.filePath != null -> {
                if (transfer.action != TransferAction.INSTALL) {
                    MenuEntry("menu:open", openLabel(transfer.action), onOpen)
                }
                MenuEntry("menu:delete", stringResource(R.string.organizer_delete), onDelete)
            }
            else -> MenuEntry("menu:remove_transfer", stringResource(R.string.organizer_remove_transfer), onRemove)
        }
        MenuEntry("", stringResource(R.string.organizer_cancel), onDismiss)
    }
}

@Composable
private fun SessionMenu(
    modifier: Modifier = Modifier,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .background(LauncherSurfaceContainerHigh, RoundedCornerShape(24.dp))
            .padding(12.dp)
            .testTag("menu"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MenuEntry("menu:disconnect", stringResource(R.string.organizer_disconnect), onDisconnect)
        MenuEntry("", stringResource(R.string.organizer_cancel), onDismiss)
    }
}

@Composable
private fun openLabel(action: TransferAction): String = when (action) {
    TransferAction.INSTALL -> stringResource(R.string.organizer_install)
    TransferAction.VIEW -> stringResource(R.string.organizer_view)
    TransferAction.PLAY -> stringResource(R.string.organizer_play)
    TransferAction.NONE -> stringResource(R.string.organizer_open)
}

@Composable
private fun MenuEntry(tag: String, label: String, onClick: () -> Unit) {
    val colors = LocalLauncherColors.current.value
    TvFocusable(
        tag = tag.ifEmpty { "menu:dismiss" },
        rowId = "menu",
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
    ) { focused ->
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = colors.onSurface,
        )
    }
}

@Composable
private fun TargetPicker(
    roots: List<StorageRoot>,
    root: String,
    path: String,
    directories: List<String>,
    onRoot: (String) -> Unit,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLauncherColors.current.value
    Column(
        modifier = modifier
            .width(520.dp)
            .background(LauncherSurfaceContainerHigh, RoundedCornerShape(28.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.organizer_save_location),
            color = colors.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        for (storageRoot in roots) {
            TvFocusable(
                tag = "target:root:${storageRoot.id}",
                rowId = "target",
                onClick = { onRoot(storageRoot.id) },
                shape = RoundedCornerShape(14.dp),
            ) { focused ->
                Text(
                    text = "${storageRoot.label} · ${formatBytes(storageRoot.free)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (storageRoot.id == root) colors.primary else colors.onSurface,
                )
            }
        }
        Text(
            text = path.ifEmpty { root },
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TvFocusable(tag = "target:up", rowId = "target", onClick = onUp, shape = RoundedCornerShape(14.dp)) { focused ->
            Text(
                text = stringResource(R.string.organizer_up),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = colors.onSurface,
            )
        }
        for (directory in directories) {
            TvFocusable(
                tag = "target:dir:$directory",
                rowId = "target",
                onClick = { onEnter(directory) },
                shape = RoundedCornerShape(14.dp),
            ) { focused ->
                Text(
                    text = directory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvFocusable(
                tag = "target:select",
                rowId = "target",
                onClick = onSelect,
                shape = RoundedCornerShape(16.dp),
            ) { focused ->
                Text(
                    text = stringResource(R.string.organizer_select_folder),
                    modifier = Modifier
                        .background(
                            if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = colors.primary,
                )
            }
            TvFocusable(
                tag = "target:cancel",
                rowId = "target",
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
            ) { focused ->
                Text(
                    text = stringResource(R.string.organizer_cancel),
                    modifier = Modifier
                        .background(
                            if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = colors.onSurface,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

@Composable
fun ConfirmPhoneOverlay(
    device: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLauncherColors.current.value
    Box(
        modifier = modifier.fillMaxSize().testTag("confirm_phone"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .background(LauncherSurfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.organizer_confirm_request, device),
                color = colors.onSurface,
                fontSize = 20.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlayButton("confirm_phone:allow", stringResource(R.string.organizer_allow), onAllow, "confirm_phone", 0)
                OverlayButton("confirm_phone:deny", stringResource(R.string.organizer_deny), onDeny, "confirm_phone", 1)
            }
        }
    }
}

@Composable
fun LeaveTransfersDialog(
    count: Int,
    onBackground: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLauncherColors.current.value
    Box(
        modifier = modifier.fillMaxSize().testTag("leave_transfers"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .background(LauncherSurfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.organizer_leave_count, count),
                color = colors.onSurface,
                fontSize = 20.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlayButton("leave:background", stringResource(R.string.organizer_leave_background), onBackground)
                OverlayButton("leave:stop", stringResource(R.string.organizer_leave_stop), onStop)
            }
        }
    }
}

@Composable
fun InstallCardOverlay(
    state: InstallCardState,
    errorText: String?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.state == "idle") return
    val colors = LocalLauncherColors.current.value
    Box(
        modifier = modifier.fillMaxSize().testTag("install"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .background(LauncherSurfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.packageName ?: stringResource(R.string.organizer_install),
                color = colors.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (state.state) {
                    "ready" -> stringResource(R.string.organizer_new_install)
                    "blocked" -> stringResource(R.string.organizer_unknown_sources)
                    "pending_user" -> stringResource(R.string.organizer_waiting)
                    "installing" -> stringResource(R.string.organizer_installing)
                    "installed" -> stringResource(R.string.organizer_installed)
                    "failed" -> stringResource(R.string.organizer_install_failed)
                    "cancelled" -> stringResource(R.string.organizer_cancelled)
                    else -> ""
                },
                color = colors.onSurface.copy(alpha = 0.75f),
            )
            if (state.state == "failed" || state.state == "blocked") {
                Text(
                    text = errorText ?: stringResource(R.string.organizer_unreadable),
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state.state) {
                    "ready" -> {
                        OverlayButton("install:ok", stringResource(R.string.organizer_install), onInstall)
                        OverlayButton("install:cancel", stringResource(R.string.organizer_cancel), onCancel)
                    }
                    "blocked" -> {
                        OverlayButton("install:settings", stringResource(R.string.organizer_settings), onSettings)
                        OverlayButton("install:cancel", stringResource(R.string.organizer_cancel), onCancel)
                    }
                    "pending_user" -> {
                        OverlayButton("install:cancel", stringResource(R.string.organizer_cancel), onCancel)
                    }
                    "installing" -> Unit
                    "installed" -> {
                        OverlayButton("install:open", stringResource(R.string.organizer_open), onOpen)
                        OverlayButton("install:done", stringResource(R.string.organizer_done), onDone)
                    }
                    else -> {
                        OverlayButton("install:done", stringResource(R.string.organizer_done), onDone)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(
    tag: String,
    label: String,
    onClick: () -> Unit,
    rowId: String? = null,
    index: Int = 0,
) {
    val colors = LocalLauncherColors.current.value
    TvFocusable(
        tag = tag,
        rowId = rowId,
        index = index,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
    ) { focused ->
        Text(
            text = label,
            modifier = Modifier
                .background(
                    if (focused) LauncherCard else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            color = colors.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}
