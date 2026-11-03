package com.github.mytv.feature.home

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.AppIconSize
import com.github.mytv.core.designsystem.ContinueCardHeight
import com.github.mytv.core.designsystem.ContinueCardWidth
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherFocusRing
import com.github.mytv.core.designsystem.LauncherOnPrimary
import com.github.mytv.core.designsystem.LauncherPrimary
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.RowHeight
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.RowStride
import com.github.mytv.core.designsystem.RowTop
import com.github.mytv.core.designsystem.rememberArtwork
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.apps.AppEntry
import com.github.mytv.data.apps.Folder
import com.github.mytv.data.channels.WatchNextEntry
import kotlin.math.roundToInt
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun HomeScreen(
    enabled: Boolean,
    hero: WatchNextEntry?,
    heroInWatchlist: Boolean,
    rows: List<HomeRowData>,
    onPlayHero: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onHeroDetails: () -> Unit,
    onNextClick: (WatchNextEntry) -> Unit,
    onAppClick: (AppEntry) -> Unit,
    onAppLongClick: (AppEntry, String) -> Unit,
    onFolderClick: (Folder, List<AppEntry>) -> Unit,
    onFolderLongClick: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalTvFocus.current
    val focusTag = focus.current?.tag
    val density = LocalDensity.current
    val focusedRow = rows.indexOfFirst { row -> row.items.any { it.tag == focusTag } }
    val scrollTarget = if (focusedRow >= 0) {
        with(density) { (RowStride * focusedRow).roundToPx() }
    } else {
        0
    }
    val scroll by animateIntAsState(targetValue = scrollTarget, animationSpec = tween(260))

    Box(modifier = modifier.fillMaxSize()) {
        HeroPanel(
            hero = hero,
            inWatchlist = heroInWatchlist,
            enabled = enabled,
            onPlay = onPlayHero,
            onToggleWatchlist = onToggleWatchlist,
            onDetails = onHeroDetails,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = RowTop)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, -scroll) },
            ) {
                for (row in rows) {
                    HomeRow(
                        row = row,
                        enabled = enabled,
                        focusTag = focusTag,
                        onNextClick = onNextClick,
                        onAppClick = onAppClick,
                        onAppLongClick = onAppLongClick,
                        onFolderClick = onFolderClick,
                        onFolderLongClick = onFolderLongClick,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroPanel(
    hero: WatchNextEntry?,
    inWatchlist: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onDetails: () -> Unit,
) {
    if (hero == null) return
    val art = rememberArtwork(hero.posterArtUri, 960, 540)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowTop)
            .testTag("hero"),
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x66000000),
                            Color(0x22000000),
                            Color(0xE6000000),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .padding(start = RowLeft, top = 92.dp)
                .width(520.dp),
        ) {
            Text(
                text = hero.title,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = watchSubtitle(hero),
                color = Color(0xFFE0E0E6),
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton(
                    tag = "hero:play",
                    label = androidx.compose.ui.res.stringResource(DsR.string.hero_play),
                    primary = true,
                    enabled = enabled,
                    onClick = onPlay,
                )
                HeroButton(
                    tag = "hero:watchlist",
                    label = androidx.compose.ui.res.stringResource(
                        if (inWatchlist) DsR.string.hero_watchlist_added else DsR.string.hero_watchlist,
                    ),
                    primary = false,
                    enabled = enabled,
                    onClick = onToggleWatchlist,
                )
                HeroButton(
                    tag = "hero:info",
                    label = androidx.compose.ui.res.stringResource(DsR.string.hero_details),
                    primary = false,
                    enabled = enabled,
                    onClick = onDetails,
                )
            }
        }
    }
}

@Composable
private fun HeroButton(
    tag: String,
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.04f,
        shape = RoundedCornerShape(23.dp),
        onClick = onClick,
    ) { focused ->
        val background = if (primary) LauncherPrimary else LauncherSurfaceContainerHigh
        val foreground = if (primary) LauncherOnPrimary else Color.White
        Box(
            modifier = Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(background)
                .border(
                    width = 2.dp,
                    color = if (focused) LauncherFocusRing else Color.Transparent,
                    shape = RoundedCornerShape(23.dp),
                )
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HomeRow(
    row: HomeRowData,
    enabled: Boolean,
    focusTag: String?,
    onNextClick: (WatchNextEntry) -> Unit,
    onAppClick: (AppEntry) -> Unit,
    onAppLongClick: (AppEntry, String) -> Unit,
    onFolderClick: (Folder, List<AppEntry>) -> Unit,
    onFolderLongClick: (Folder) -> Unit,
) {
    val density = LocalDensity.current
    val gap = if (row.id == "apps") 18.dp else 16.dp
    val focusedIndex = row.items.indexOfFirst { it.tag == focusTag }
    var before = 0f
    if (focusedIndex > 0) {
        for (i in 0 until focusedIndex) {
            before += itemWidth(row.items[i]) + gap.value
        }
    }
    val target = with(density) {
        (RowLeft - before.dp).roundToPx()
    }
    val offset by animateIntAsState(targetValue = target, animationSpec = tween(260))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .testTag("row:${row.id}"),
    ) {
        val title = when (row.id) {
            "continue" -> androidx.compose.ui.res.stringResource(DsR.string.row_continue)
            "watchlist" -> androidx.compose.ui.res.stringResource(DsR.string.row_watchlist)
            "apps" -> androidx.compose.ui.res.stringResource(DsR.string.row_apps)
            else -> ""
        }
        Text(
            text = title,
            color = Color(0xFFDAD6E0),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = RowLeft)
                .height(28.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .clipToBounds(),
        ) {
            Row(
                modifier = Modifier.offset { IntOffset(offset, 0) },
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Top,
            ) {
                row.items.forEachIndexed { index, item ->
                    when (item) {
                        is HomeItem.Next -> NextCard(
                            entry = item.entry,
                            inWatchlist = item.inWatchlist,
                            tag = item.tag,
                            rowId = row.id,
                            index = index,
                            enabled = enabled,
                            onClick = { onNextClick(item.entry) },
                        )
                        is HomeItem.App -> AppTile(
                            app = item.app,
                            tag = item.tag,
                            rowId = row.id,
                            index = index,
                            enabled = enabled,
                            showLabelWhenFocused = true,
                            onClick = { onAppClick(item.app) },
                            onLongClick = { onAppLongClick(item.app, row.id) },
                        )
                        is HomeItem.FolderTile -> FolderTile(
                            folder = item.folder,
                            apps = item.apps,
                            tag = item.tag,
                            rowId = row.id,
                            index = index,
                            enabled = enabled,
                            onClick = { onFolderClick(item.folder, item.apps) },
                            onLongClick = { onFolderLongClick(item.folder) },
                        )
                    }
                }
            }
        }
    }
}

private fun itemWidth(item: HomeItem): Float = when (item) {
    is HomeItem.Next -> ContinueCardWidth.value
    is HomeItem.App, is HomeItem.FolderTile -> AppIconSize.value
}

@Composable
private fun NextCard(
    entry: WatchNextEntry,
    inWatchlist: Boolean,
    tag: String,
    rowId: String,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.08f,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
    ) { focused ->
        Column(modifier = Modifier.width(ContinueCardWidth)) {
            val art = rememberArtwork(entry.posterArtUri, 400, 225)
            Box(
                modifier = Modifier
                    .width(ContinueCardWidth)
                    .height(ContinueCardHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (focused) LauncherCardFocused else LauncherCard)
                    .border(
                        width = 2.dp,
                        color = if (focused) LauncherFocusRing else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0x99000000)),
                                ),
                            ),
                    )
                }
                Text(
                    text = entry.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
                val progress = if (entry.durationMillis > 0) {
                    (entry.playbackPositionMillis.toFloat() / entry.durationMillis).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x55FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(LauncherPrimary),
                    )
                }
            }
            if (focused) {
                Text(
                    text = watchSubtitle(entry),
                    color = Color(0xFFDAD6E0),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(ContinueCardWidth)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun AppTile(
    app: AppEntry,
    tag: String,
    rowId: String?,
    index: Int,
    enabled: Boolean,
    showLabelWhenFocused: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    TvFocusable(
        tag = tag,
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.12f,
        shape = CircleShape,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    ) { focused ->
        Column(
            modifier = Modifier.width(AppIconSize),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(AppIconSize)
                    .clip(CircleShape)
                    .background(LauncherCard),
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
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (!showLabelWhenFocused || focused) {
                Text(
                    text = app.label,
                    color = if (focused) Color.White else Color(0xFFCAC4D0),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(AppIconSize)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderTile(
    folder: Folder,
    apps: List<AppEntry>,
    tag: String,
    rowId: String,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.12f,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        onLongClick = onLongClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .width(AppIconSize)
                .height(90.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(AppIconSize)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (focused) LauncherCardFocused else LauncherCard)
                    .border(
                        width = 2.dp,
                        color = if (focused) LauncherFocusRing else Color.Transparent,
                        shape = RoundedCornerShape(18.dp),
                    )
                    .padding(6.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (r in 0 until 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            for (c in 0 until 3) {
                                val app = apps.getOrNull(r * 3 + c)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
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
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(AppIconSize),
            )
        }
    }
}

@Composable
fun watchSubtitle(entry: WatchNextEntry): String {
    val remaining = androidx.compose.ui.res.stringResource(
        R.string.watch_next_remaining,
        entry.remainingMinutes,
    )
    val episode = entry.episodeNumber
    val season = entry.seasonNumber
    return when {
        season != null && episode != null -> {
            androidx.compose.ui.res.stringResource(R.string.watch_next_season, season, episode) + " · " + remaining
        }
        episode != null -> {
            androidx.compose.ui.res.stringResource(R.string.watch_next_episode, episode) + " · " + remaining
        }
        else -> remaining
    }
}

