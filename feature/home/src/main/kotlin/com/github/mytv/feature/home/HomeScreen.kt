package com.github.mytv.feature.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.github.mytv.core.designsystem.AppIconSize
import com.github.mytv.core.designsystem.ContinueCardHeight
import com.github.mytv.core.designsystem.ContinueCardWidth
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.LauncherWavyProgress
import com.github.mytv.core.designsystem.LocalLauncherColors
import com.github.mytv.core.designsystem.LocalRowRecompositionObserver
import com.github.mytv.core.designsystem.LocalThemeTransitioning
import com.github.mytv.core.designsystem.RowHeight
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.RowStride
import com.github.mytv.core.designsystem.RowTop
import com.github.mytv.core.designsystem.launcherDefaultSpatialSpec
import com.github.mytv.core.designsystem.launcherFastSpatialSpec
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.designsystem.rememberArtwork
import com.github.mytv.core.designsystem.rememberCookieMorphShape
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
    onProgramClick: (com.github.mytv.data.channels.ChannelProgram) -> Unit,
    onProgramLongClick: (com.github.mytv.data.channels.ChannelProgram) -> Unit,
    onManageChannels: () -> Unit,
    forceHero: Boolean = false,
    widgetRow: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focus = LocalTvFocus.current
    val focusTag = focus.current?.tag
    val density = LocalDensity.current
    val focusedRow = rows.indexOfFirst { row -> row.items.any { it.tag == focusTag } }
    val rowMode = !forceHero && focusedRow >= 0
    val scrollTarget = if (rowMode) {
        with(density) { (RowStride * focusedRow).roundToPx() }
    } else {
        0
    }
    val scroll = animateIntAsState(targetValue = scrollTarget, animationSpec = tween(260))

    Box(modifier = modifier.fillMaxSize()) {
        HeroPanel(
            hero = hero,
            inWatchlist = heroInWatchlist,
            enabled = enabled,
            onPlay = onPlayHero,
            onToggleWatchlist = onToggleWatchlist,
            onDetails = onHeroDetails,
            modifier = Modifier
                .zIndex(if (rowMode) 0f else 1f)
                .alpha(if (rowMode) 0f else 1f),
        )
        if (rowMode) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RowTop)
                    .pointerInput(Unit) { detectTapGestures { } },
            )
            InfoHeader(focusTag, rows)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = RowTop)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .offset { IntOffset(0, -scroll.value) },
            ) {
                for (row in rows) {
                    if (row.id == "widgets" && widgetRow != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RowHeight)
                                .testTag("row:${row.id}"),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(146.dp),
                            ) {
                                widgetRow(Modifier.padding(start = RowLeft))
                            }
                        }
                    } else {
                        HomeRow(
                            row = row,
                            enabled = enabled,
                            focusTag = focusTag,
                            onNextClick = onNextClick,
                            onAppClick = onAppClick,
                            onAppLongClick = onAppLongClick,
                            onFolderClick = onFolderClick,
                            onFolderLongClick = onFolderLongClick,
                            onProgramClick = onProgramClick,
                            onProgramLongClick = onProgramLongClick,
                            onManageChannels = onManageChannels,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoHeader(focusTag: String?, rows: List<HomeRowData>) {
    val entry = rows.asSequence()
        .flatMap { it.items.asSequence() }
        .filterIsInstance<HomeItem.Next>()
        .firstOrNull { it.tag == focusTag }
        ?.entry ?: return
    Column(
        modifier = Modifier
            .padding(start = RowLeft, top = 86.dp)
            .width(640.dp)
            .testTag("info"),
    ) {
        Text(
            text = entry.title,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = watchSubtitle(entry),
            color = Color(0xFFDAD6E0),
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
        entry.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color(0xFFCAC4D0),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
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
    modifier: Modifier = Modifier,
) {
    if (hero == null) return
    val colors = LocalLauncherColors.current.value
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("hero"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowTop)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x33000000),
                            Color(0x11000000),
                            Color(0x99000000),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .padding(start = RowLeft, top = 88.dp)
                .width(500.dp),
        ) {
            Text(
                text = hero.title,
                color = Color.White,
                fontSize = 48.sp,
                lineHeight = 51.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = watchSubtitle(hero),
                color = Color(0xFFE0E0E6),
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                HeroButton(
                    tag = "hero:play",
                    label = androidx.compose.ui.res.stringResource(DsR.string.hero_play),
                    primary = true,
                    colors = colors,
                    enabled = enabled,
                    onClick = onPlay,
                )
                HeroButton(
                    tag = "hero:watchlist",
                    label = androidx.compose.ui.res.stringResource(
                        if (inWatchlist) DsR.string.hero_watchlist_added else DsR.string.hero_watchlist,
                    ),
                    primary = false,
                    colors = colors,
                    enabled = enabled,
                    onClick = onToggleWatchlist,
                )
                HeroButton(
                    tag = "hero:info",
                    label = androidx.compose.ui.res.stringResource(DsR.string.hero_details),
                    primary = false,
                    colors = colors,
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
    colors: LauncherColors,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (primary) colors.primary else colors.surfaceContainerHigh
    val foreground = if (primary) colors.onPrimary else colors.onSurface
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.05f,
        shape = RoundedCornerShape(23.dp),
        onClick = onClick,
    ) { focused ->
        val radius by animateDpAsState(
            targetValue = if (focused) 14.dp else 23.dp,
            animationSpec = launcherFastSpatialSpec(),
            label = "buttonRadius",
        )
        val horizontal by animateDpAsState(
            targetValue = if (focused) 34.dp else 24.dp,
            animationSpec = launcherDefaultSpatialSpec(),
            label = "buttonPadding",
        )
        val shape = RoundedCornerShape(radius)
        Box(
            modifier = Modifier
                .height(46.dp)
                .clip(shape)
                .background(background)
                .launcherFocusRing(focused, shape)
                .padding(horizontal = horizontal),
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
    onProgramClick: (com.github.mytv.data.channels.ChannelProgram) -> Unit,
    onProgramLongClick: (com.github.mytv.data.channels.ChannelProgram) -> Unit,
    onManageChannels: () -> Unit,
) {
    val density = LocalDensity.current
    val transitioning = LocalThemeTransitioning.current
    val observer = LocalRowRecompositionObserver.current
    SideEffect {
        if (transitioning.value) observer?.invoke()
    }
    val gap = if (row.id == "apps") 18.dp else 16.dp
    val focusedIndex = row.items.indexOfFirst { it.tag == focusTag }
    var before = 0f
    if (focusedIndex > 0) {
        for (i in 0 until focusedIndex) {
            before += itemWidth(row.items[i]) + gap.value
        }
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isChannelRow = row.id.startsWith("ch:")
    val screenPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val starts = FloatArray(row.items.size)
    val widths = FloatArray(row.items.size)
    run {
        var acc = 0f
        row.items.forEachIndexed { i, item ->
            starts[i] = acc
            widths[i] = itemWidth(item)
            acc += widths[i] + gap.value
        }
    }
    var channelScroll by androidx.compose.runtime.remember(row.id) {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val target = if (isChannelRow && row.items.isNotEmpty()) {
        val marginPx = with(density) { RowLeft.roundToPx() }
        val totalPx = with(density) {
            var sum = 0f
            row.items.forEachIndexed { i, item ->
                sum += itemWidth(item)
                if (i < row.items.lastIndex) sum += gap.value
            }
            sum.dp.roundToPx()
        }
        val focusItem = row.items.getOrNull(focusedIndex) ?: row.items.first()
        val itemPx = with(density) { itemWidth(focusItem).dp.roundToPx() }
        val startPx = with(density) { before.dp.roundToPx() }
        val maxScroll = minOf(marginPx, screenPx - marginPx - totalPx)
        var want = channelScroll
        val itemStart = want + startPx
        val itemEnd = itemStart + itemPx
        if (itemStart < marginPx) {
            want = marginPx - startPx
        } else if (itemEnd > screenPx - marginPx) {
            want = screenPx - marginPx - itemPx - startPx
        }
        want.coerceIn(maxScroll, marginPx)
    } else {
        with(density) { (RowLeft - before.dp).roundToPx() }
    }
    androidx.compose.runtime.LaunchedEffect(target) {
        if (isChannelRow) channelScroll = target
    }
    val offset = animateIntAsState(targetValue = target, animationSpec = tween(260))

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
            else -> row.title
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
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(offset.value, 0) },
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Top,
            ) {
                row.items.forEachIndexed { index, item ->
                    val itemStart = with(density) { starts[index].dp.roundToPx() } + target
                    val itemEnd = itemStart + with(density) { widths[index].dp.roundToPx() }
                    val fullyVisible = item.tag == focusTag ||
                        (itemStart >= 0 && itemEnd <= screenPx)
                    Box(Modifier.alpha(if (fullyVisible) 1f else 0f)) {
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
                        is HomeItem.Program -> ProgramCard(
                            program = item.program,
                            rowId = row.id,
                            index = index,
                            enabled = enabled,
                            onClick = { onProgramClick(item.program) },
                            onLongClick = { onProgramLongClick(item.program) },
                        )
                        is HomeItem.ManageChannels -> ManageChannelsCard(
                            rowId = row.id,
                            index = index,
                            enabled = enabled,
                            onClick = onManageChannels,
                        )
                        is HomeItem.Widget -> Unit
                    }
                    }
                }
            }
        }
    }
}

private fun itemWidth(item: HomeItem): Float = when (item) {
    is HomeItem.Next -> ContinueCardWidth.value
    is HomeItem.App, is HomeItem.FolderTile -> AppIconSize.value
    is HomeItem.Program -> 144f * item.program.widthRatio
    is HomeItem.ManageChannels -> 220f
    is HomeItem.Widget -> 236f
}

@Composable
private fun ProgramCard(
    program: com.github.mytv.data.channels.ChannelProgram,
    rowId: String,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalLauncherColors.current.value
    val width = (144f * program.widthRatio).dp
    TvFocusable(
        tag = program.tag,
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.06f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        onLongClick = onLongClick,
    ) { focused ->
        val shape = RoundedCornerShape(if (focused) 18.dp else 12.dp)
        Box(
            modifier = Modifier
                .width(width)
                .height(144.dp)
                .clip(shape)
                .background(colors.primaryContainer)
                .launcherFocusRing(focused, shape),
        ) {
            val art = rememberArtwork(program.posterArtUri, 480, 480)
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = program.title,
                    color = colors.onPrimaryContainer,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            }
            if (program.isChannel) {
                Text(
                    text = androidx.compose.ui.res.stringResource(DsR.string.live_badge),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCCD32F2F))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ManageChannelsCard(
    rowId: String,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = "manage_channels",
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.04f,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) { focused ->
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(56.dp)
                .clip(shape)
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .launcherFocusRing(focused, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(DsR.string.manage_channels),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
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
    val colors = LocalLauncherColors.current.value
    TvFocusable(
        tag = tag,
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.1f,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
    ) { focused ->
        val radius by animateDpAsState(
            targetValue = if (focused) 24.dp else 16.dp,
            animationSpec = launcherFastSpatialSpec(),
            label = "cardRadius",
        )
        val shape = RoundedCornerShape(radius)
        Column(modifier = Modifier.width(ContinueCardWidth)) {
            val art = rememberArtwork(entry.posterArtUri, 400, 225)
            Box(
                modifier = Modifier
                    .width(ContinueCardWidth)
                    .height(ContinueCardHeight)
                    .clip(shape)
                    .background(if (focused) LauncherCardFocused else LauncherCard)
                    .launcherFocusRing(focused, shape),
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
                        .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                )
                val progress = if (entry.durationMillis > 0) {
                    (entry.playbackPositionMillis.toFloat() / entry.durationMillis).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LauncherWavyProgress(
                    progress = progress,
                    color = colors.primary,
                    trackColor = Color(0x55FFFFFF),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .height(10.dp),
                )
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
        val morph = animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = launcherDefaultSpatialSpec(),
            label = "cookie",
        )
        val shape = rememberCookieMorphShape(morph)
        Column(
            modifier = Modifier.width(AppIconSize),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(AppIconSize)
                    .launcherFocusRing(focused, shape)
                    .graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
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
                    color = Color.White,
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
    val shape = RoundedCornerShape(18.dp)
    TvFocusable(
        tag = tag,
        rowId = rowId,
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.12f,
        shape = shape,
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
                    .clip(shape)
                    .background(if (focused) LauncherCardFocused else LauncherCard)
                    .launcherFocusRing(focused, shape)
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
                color = Color.White,
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
