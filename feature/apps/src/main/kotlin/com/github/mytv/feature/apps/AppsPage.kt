package com.github.mytv.feature.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherFocusRing
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.rememberArtwork
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.apps.AppEntry
import com.github.mytv.data.apps.Folder
import com.github.mytv.core.designsystem.R as DsR

sealed interface AppsGridItem {
    val tag: String

    data class App(val app: AppEntry) : AppsGridItem {
        override val tag: String
            get() = app.tag
    }

    data class FolderTile(
        val folder: Folder,
        val apps: List<AppEntry>,
    ) : AppsGridItem {
        override val tag: String
            get() = "folder:${folder.id}"
    }
}

@Composable
fun AppsPage(
    items: List<AppsGridItem>,
    enabled: Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongClick: (AppEntry) -> Unit,
    onFolderClick: (Folder, List<AppEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0E0D11))) {
        Column(modifier = Modifier.padding(start = RowLeft, top = 92.dp)) {
            Text(
                text = stringResource(DsR.string.page_apps_title),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier.padding(top = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (rowStart in items.indices step 5) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (index in rowStart until minOf(rowStart + 5, items.size)) {
                            when (val item = items[index]) {
                                is AppsGridItem.App -> AppGridCard(
                                    app = item.app,
                                    enabled = enabled,
                                    onClick = { onAppClick(item.app) },
                                    onLongClick = { onAppLongClick(item.app) },
                                )
                                is AppsGridItem.FolderTile -> FolderGridCard(
                                    folder = item.folder,
                                    apps = item.apps,
                                    enabled = enabled,
                                    onClick = { onFolderClick(item.folder, item.apps) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGridCard(
    app: AppEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    TvFocusable(
        tag = app.tag,
        enabled = enabled,
        scaleOnFocus = 1.05f,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        onLongClick = onLongClick,
    ) { focused ->
        Column(
            modifier = Modifier.width(160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (focused) LauncherCardFocused else LauncherCard)
                    .border(
                        width = 2.dp,
                        color = if (focused) LauncherFocusRing else Color.Transparent,
                        shape = RoundedCornerShape(18.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val banner = app.banner
                if (banner != null) {
                    Image(
                        bitmap = banner.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.size(58.dp).clip(CircleShape).background(LauncherSurfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text(
                                text = app.label.take(1),
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Text(
                text = app.label,
                color = if (focused) Color.White else Color(0xFFCAC4D0),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(160.dp)
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun FolderGridCard(
    folder: Folder,
    apps: List<AppEntry>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = "folder:${folder.id}",
        enabled = enabled,
        scaleOnFocus = 1.05f,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
    ) { focused ->
        Column(
            modifier = Modifier.width(160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (focused) LauncherCardFocused else LauncherCard)
                    .border(
                        width = 2.dp,
                        color = if (focused) LauncherFocusRing else Color.Transparent,
                        shape = RoundedCornerShape(18.dp),
                    )
                    .padding(8.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (r in 0 until 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (c in 0 until 3) {
                                val app = apps.getOrNull(r * 3 + c)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(LauncherSurfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val icon = app?.icon
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(
                text = folder.name,
                color = if (focused) Color.White else Color(0xFFCAC4D0),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(160.dp)
                    .padding(top = 4.dp),
            )
        }
    }
}
