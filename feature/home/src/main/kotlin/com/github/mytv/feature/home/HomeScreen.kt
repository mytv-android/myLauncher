package com.github.mytv.feature.home

import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.github.mytv.core.designsystem.AppIconSize
import com.github.mytv.core.designsystem.ContinueCardHeight
import com.github.mytv.core.designsystem.ContinueCardWidth
import com.github.mytv.core.designsystem.Glyphs
import com.github.mytv.core.designsystem.LauncherGlyph
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
    heroItems: List<HeroItem>,
    heroIndex: Int,
    heroInWatchlist: Boolean,
    rows: List<HomeRowData>,
    appOf: (String) -> AppEntry?,
    onPlayHero: () -> Unit,
    onToggleWatchlist: () -> Unit,
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
    val heroShown = heroItems.isNotEmpty() && !rowMode
    val scrollTarget = when {
        rowMode -> with(density) { (RowStride * focusedRow).roundToPx() }
        // Hero 展开：第一行露在 Hero 下方（y 392），其余行在屏幕外
        heroShown -> with(density) { (RowTop - HeroRowsTop).roundToPx() }
        else -> 0
    }
    val scroll = animateIntAsState(targetValue = scrollTarget, animationSpec = tween(320))
    val heroAlpha by animateFloatAsState(
        targetValue = if (rowMode) 0f else 1f,
        animationSpec = tween(320),
        label = "heroAlpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        HeroPanel(
            items = heroItems,
            index = heroIndex,
            inWatchlist = heroInWatchlist,
            // 行内时 Hero 透明但按钮仍可接收焦点请求：BACK 链 行 → Hero → 顶栏（§09）
            enabled = enabled,
            onPlay = onPlayHero,
            onToggleWatchlist = onToggleWatchlist,
            modifier = Modifier
                .zIndex(if (rowMode) 0f else 1f)
                .graphicsLayer {
                    alpha = heroAlpha
                    translationY = (heroAlpha - 1f) * 28.dp.toPx()
                },
        )
        if (rowMode) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RowTop)
                    .pointerInput(Unit) { detectTapGestures { } },
            )
            InfoHeader(focusTag, rows, appOf)
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
private fun InfoHeader(
    focusTag: String?,
    rows: List<HomeRowData>,
    appOf: (String) -> AppEntry?,
) {
    var source: String? = null
    var badgePkg: String? = null
    var title: String? = null
    var meta: String? = null
    var description: String? = null
    for (row in rows) {
        val item = row.items.firstOrNull { it.tag == focusTag } ?: continue
        when (item) {
            is HomeItem.Next -> {
                source = appOf(item.entry.packageName)?.label
                badgePkg = item.entry.packageName
                title = item.entry.title
                meta = watchSubtitle(item.entry)
                description = item.entry.description
            }
            is HomeItem.Program -> {
                source = row.title
                badgePkg = item.program.packageName
                title = item.program.title
                meta = programMeta(item.program.genre, item.program.releaseDate)
                description = item.program.description
            }
            else -> Unit
        }
        break
    }
    val shownTitle = title ?: return
    val colors = LocalLauncherColors.current.value
    Column(
        modifier = Modifier
            .padding(start = RowLeft, top = 86.dp)
            .width(560.dp)
            .testTag("info"),
    ) {
        SourceLine(source, badgePkg?.let { appOf(it)?.icon }, colors)
        Text(
            text = shownTitle,
            color = colors.onSurface,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        meta?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = colors.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(520.dp),
            )
        }
    }
}

@Composable
private fun SourceLine(text: String?, badge: android.graphics.Bitmap?, colors: LauncherColors) {
    if (text.isNullOrBlank() && badge == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(20.dp),
    ) {
        if (badge != null) {
            Image(
                bitmap = badge.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape),
            )
        }
        if (!text.isNullOrBlank()) {
            Text(
                text = text,
                color = colors.onSurfaceVariant,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.25.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun programMeta(genre: String?, releaseDate: String?): String =
    listOfNotNull(
        genre?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        releaseDate?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

/** Hero 展开时内容行的顶部（原型 layout()：hero 模式 translateY 392）。 */
internal val HeroRowsTop = 392.dp

@Composable
private fun HeroPanel(
    items: List<HeroItem>,
    index: Int,
    inWatchlist: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hero = items.getOrNull(index) ?: return
    val colors = LocalLauncherColors.current.value
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HeroRowsTop)
            .testTag("hero"),
    ) {
        Column(
            modifier = Modifier
                .padding(start = RowLeft, top = 98.dp)
                .width(500.dp),
        ) {
            Crossfade(
                targetState = hero,
                animationSpec = tween(320),
                label = "heroText",
            ) { item ->
                HeroText(item, colors)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .height(48.dp),
            ) {
                HeroButton(
                    tag = "hero:play",
                    label = stringResource(
                        if (hero.entry.playbackPositionMillis > 0) DsR.string.hero_resume else DsR.string.hero_play,
                    ),
                    glyph = Glyphs.PLAY,
                    primary = true,
                    colors = colors,
                    enabled = enabled,
                    onClick = onPlay,
                )
                HeroButton(
                    tag = "hero:watchlist",
                    label = stringResource(
                        if (inWatchlist) DsR.string.hero_watchlist_added else DsR.string.hero_watchlist,
                    ),
                    glyph = if (inWatchlist) Glyphs.CHECK else Glyphs.PLUS,
                    primary = false,
                    colors = colors,
                    enabled = enabled,
                    onClick = onToggleWatchlist,
                )
            }
            if (items.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    items.forEachIndexed { i, _ ->
                        val on = i == index
                        val width by animateDpAsState(
                            targetValue = if (on) 28.dp else 8.dp,
                            animationSpec = launcherDefaultSpatialSpec(),
                            label = "heroDot",
                        )
                        Box(
                            modifier = Modifier
                                .width(width)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (on) colors.onSurface else colors.onSurface.copy(alpha = 0.32f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroText(item: HeroItem, colors: LauncherColors) {
    var titleLines by remember(item.key) { mutableIntStateOf(1) }
    Column {
        SourceLine(item.source, item.badge, colors)
        Text(
            text = item.entry.title,
            color = colors.onSurface,
            fontSize = 48.sp,
            lineHeight = 51.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.48.sp,
            style = TextStyle(
                shadow = Shadow(color = Color(0x40000000), offset = Offset(0f, 2f), blurRadius = 24f),
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { titleLines = it.lineCount },
            modifier = Modifier.padding(top = 10.dp),
        )
        val meta = if (item.fromChannel) programMeta(item.genre, item.releaseDate) else watchSubtitle(item.entry)
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                color = colors.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        item.entry.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                // 标题占两行时描述只留一行，按钮与指示点不压到第一行内容
                maxLines = if (titleLines >= 2) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(450.dp),
            )
        }
    }
}

@Composable
private fun HeroButton(
    tag: String,
    label: String,
    glyph: String,
    primary: Boolean,
    colors: LauncherColors,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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
            targetValue = if (focused) 30.dp else 20.dp,
            animationSpec = launcherDefaultSpatialSpec(),
            label = "buttonPadding",
        )
        val background = when {
            primary -> colors.primary
            focused -> colors.onSurface
            else -> colors.onSurface.copy(alpha = 0.15f)
        }
        val foreground = when {
            primary -> colors.onPrimary
            focused -> colors.surface
            else -> colors.onSurface
        }
        val shape = RoundedCornerShape(radius)
        Row(
            modifier = Modifier
                .height(46.dp)
                .clip(shape)
                .background(background)
                .launcherFocusRing(focused, shape)
                .padding(horizontal = horizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LauncherGlyph(path = glyph, color = foreground, size = 18.dp)
            Text(text = label, color = foreground, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
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
    // 时长未知时算不出剩余时间，不显示「剩 0 分钟」
    val remaining = if (entry.durationMillis > 0) {
        stringResource(R.string.watch_next_remaining, entry.remainingMinutes)
    } else {
        null
    }
    val episode = entry.episodeNumber
    val season = entry.seasonNumber
    val head = when {
        season != null && episode != null -> stringResource(R.string.watch_next_season, season, episode)
        episode != null -> stringResource(R.string.watch_next_episode, episode)
        else -> null
    }
    return listOfNotNull(head, remaining).joinToString(" · ")
}
