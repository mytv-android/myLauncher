package com.github.mytv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherScrim
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.apps.AppEntry
import com.github.mytv.data.apps.Folder
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun MenuOverlay(
    actions: List<MenuAction>,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LauncherScrim)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 64.dp)
                .width(340.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .padding(16.dp)
                .testTag("menu"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                TvFocusable(
                    tag = action.tag,
                    scaleOnFocus = 1.02f,
                    shape = RoundedCornerShape(14.dp),
                    onClick = { onAction(action.tag) },
                ) { focused ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                            .launcherFocusRing(focused, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(text = action.label, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun FolderPickOverlay(
    folders: List<Folder>,
    stage: String,
    onPickExisting: (Folder) -> Unit,
    onNew: () -> Unit,
    onPickName: (String, String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LauncherScrim)
            .pointerInput(Unit) {
                detectTapGestures { onCancel() }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(620.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .padding(24.dp)
                .testTag("folder_pick"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(if (stage == "names") DsR.string.folder_new else DsR.string.menu_folder),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (stage == "names") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PresetName("folder_name:av", stringResource(DsR.string.folder_av), onPickName)
                    PresetName("folder_name:game", stringResource(DsR.string.folder_game), onPickName)
                    PresetName("folder_name:tools", stringResource(DsR.string.folder_tools), onPickName)
                    PresetName("folder_name:study", stringResource(DsR.string.folder_study), onPickName)
                    PresetName("folder_name:other", stringResource(DsR.string.folder_other), onPickName)
                }
            } else {
                for (folder in folders) {
                    FolderPickRow(
                        tag = "folder_pick:${folder.id}",
                        label = folder.name,
                        onClick = { onPickExisting(folder) },
                    )
                }
                FolderPickRow(
                    tag = "folder_pick:new",
                    label = stringResource(DsR.string.folder_new),
                    onClick = onNew,
                )
            }
            FolderPickRow(
                tag = "folder_pick:cancel",
                label = stringResource(DsR.string.cancel),
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun PresetName(tag: String, label: String, onPick: (String, String) -> Unit) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.04f,
        shape = RoundedCornerShape(14.dp),
        onClick = { onPick(tag.substringAfter("folder_name:"), label) },
    ) { focused ->
        Box(
            modifier = Modifier
                .width(104.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                .launcherFocusRing(focused, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun FolderPickRow(tag: String, label: String, onClick: () -> Unit) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.02f,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                .launcherFocusRing(focused, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = label, color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
fun FolderOpenOverlay(
    folder: Folder,
    apps: List<AppEntry>,
    onAppClick: (AppEntry) -> Unit,
    onAppLongClick: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LauncherScrim)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(608.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(LauncherCard)
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .padding(28.dp)
                .testTag("folder_open"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = folder.name,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            for (rowStart in apps.indices step 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (index in rowStart until minOf(rowStart + 3, apps.size)) {
                        val app = apps[index]
                        AppTile(
                            app = app,
                            tag = app.tag,
                            rowId = null,
                            index = index,
                            enabled = true,
                            showLabelWhenFocused = true,
                            onClick = { onAppClick(app) },
                            onLongClick = { onAppLongClick(app) },
                        )
                    }
                }
            }
        }
    }
}
