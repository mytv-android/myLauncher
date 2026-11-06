package com.github.mytv.feature.quicksettings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.Glyphs
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.LauncherGlyph
import com.github.mytv.core.designsystem.launcherDefaultSpatialSpec
import com.github.mytv.core.designsystem.launcherFastSpatialSpec
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.glass.GlassStyle
import com.github.mytv.core.glass.GlassSurface
import com.github.mytv.core.glass.GlassTier
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.core.tvfocus.TvFocusable
import dev.chrisbanes.haze.HazeInput
import kotlinx.coroutines.withTimeoutOrNull
import com.github.mytv.core.designsystem.R as DsR

data class QuickSettingsValues(
    val colorSource: String = "content",
    val glassTier: String = "auto",
    val schemeStyle: String = "tonal",
    /** 「11月6日 周五 · 九月廿八」：顶栏只放时钟，日期与农历在快速设置标题下。 */
    val date: String = "",
    val volume: Int = 0,
    val volumeMax: Int = 15,
    val muted: Boolean = false,
    val wifiOn: Boolean = false,
    val bluetoothOn: Boolean = false,
    val dndOn: Boolean = false,
    /** 能切换勿扰（已获勿扰权限，或系统有授权页）；否则不显示勿扰磁贴。 */
    val dndAvailable: Boolean = false,
    /** 儿童 / 长辈模式开启：显示「家长」（§14）。 */
    val restricted: Boolean = false,
)

/**
 * 面板的焦点网格，自上而下，每行从左到右。宿主按它处理方向键：上下换行并保持列（取较小者），
 * 左右在行内移动；音量与三组设置的左右键改为调节数值。
 *
 * 左列放 Wi-Fi、信号源、屏保、家长，右列放蓝牙、勿扰、环境模式、传文件；一列比另一列长时，
 * 多出的一项独占一行。
 */
fun quickSettingsRows(values: QuickSettingsValues): List<List<String>> {
    val left = buildList {
        add("qs:wifi")
        add("qs:inputs")
        add("qs:saver")
        if (values.restricted) add("qs:parent")
    }
    val right = buildList {
        add("qs:bt")
        if (values.dndAvailable) add("qs:dnd")
        add("qs:ambient")
        add("qs:transfer")
    }
    val tiles = (0 until maxOf(left.size, right.size)).map { i ->
        listOfNotNull(left.getOrNull(i), right.getOrNull(i))
    }
    return listOf(listOf("qs:launcher_settings")) + tiles + listOf(
        listOf("qs:volume"),
        listOf("qs:source"),
        listOf("qs:glass"),
        listOf("qs:style"),
    )
}

const val QUICK_SETTINGS_FIRST_FOCUS = "qs:wifi"

@Composable
fun QuickSettingsOverlay(
    values: QuickSettingsValues,
    tier: GlassTier,
    colors: LauncherColors,
    input: HazeInput?,
    onAdjust: (String, Int) -> Unit,
    onLauncherSettings: () -> Unit,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 面板从右侧滑入（spatial slow）
    val slide = remember { Animatable(1f) }
    val slideSpec = launcherDefaultSpatialSpec<Float>()
    LaunchedEffect(Unit) { slide.animateTo(0f, slideSpec) }
    // 面板是模态的，打开后由它接管焦点。打开瞬间节点可能还没排布完成（滑入动画与高负载下更慢），
    // 逐帧重试到焦点落进面板为止，避免焦点留在顶栏、方向键推不动面板。
    val focusState = LocalTvFocus.current
    LaunchedEffect(Unit) {
        withTimeoutOrNull(2000) {
            while (true) {
                val tag = focusState.current?.tag
                if (tag != null && tag.startsWith("qs:") && focusState.isRegistered(tag)) return@withTimeoutOrNull
                focusState.request(QUICK_SETTINGS_FIRST_FOCUS)
                withFrameNanos { }
            }
        }
    }
    val rows = quickSettingsRows(values)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x61000000))
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        GlassSurface(
            tier = tier,
            colors = colors,
            input = input,
            style = GlassStyle.Panel,
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(14.dp)
                .fillMaxHeight()
                .width(340.dp)
                .graphicsLayer { translationX = slide.value * 380.dp.toPx() }
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 14.dp)
                    .testTag("qs"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(values, colors, onLauncherSettings)
                val tileRows = rows.filter { row -> row.any { it in TILE_TAGS } }
                // 原型磁贴高 46；多出第四行（儿童 / 长辈模式、可用勿扰）时压到 36，面板不滚动也能放下全部条目
                val tileHeight = if (tileRows.size > 3) 36.dp else 46.dp
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in tileRows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (tag in row) {
                                Box(Modifier.weight(1f)) {
                                    QsTile(tag, values, colors, tileHeight) { onAction(it) }
                                }
                            }
                        }
                    }
                }
                Labeled(stringResource(R.string.qs_volume), colors) {
                    VolumeSlider(values, colors, onToggleMute = { onAction("mute") })
                }
                Labeled(stringResource(R.string.qs_source), colors) {
                    ConnectedGroup(
                        tag = "qs:source",
                        options = listOf("content", "icon", "wallpaper", "fixed"),
                        selected = values.colorSource,
                        label = { sourceLabel(it) },
                        colors = colors,
                        onClick = { onAdjust("color_source", 1) },
                    )
                }
                Labeled(stringResource(R.string.qs_glass), colors) {
                    ConnectedGroup(
                        tag = "qs:glass",
                        options = listOf("auto", "T1", "T2", "T3"),
                        selected = values.glassTier,
                        label = { glassLabel(it) },
                        colors = colors,
                        onClick = { onAdjust("glass_tier", 1) },
                    )
                }
                Labeled(stringResource(R.string.qs_style), colors) {
                    ConnectedGroup(
                        tag = "qs:style",
                        options = listOf("tonal", "content", "vibrant", "expressive"),
                        selected = values.schemeStyle,
                        label = { styleLabel(it) },
                        colors = colors,
                        onClick = { onAdjust("scheme_style", 1) },
                    )
                }
            }
        }
    }
}

/** 标题与日期不加字体内边距，两行贴紧。 */
private val TightLines = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

private val TILE_TAGS = setOf(
    "qs:wifi", "qs:bt", "qs:dnd", "qs:ambient", "qs:inputs", "qs:saver", "qs:parent", "qs:transfer",
)

@Composable
private fun Header(values: QuickSettingsValues, colors: LauncherColors, onLauncherSettings: () -> Unit) {
    // 两行（标题、日期与农历）按内容定高：中文走回退字体，系统会按回退字体的字高放大行距，固定行高会裁掉第二行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(DsR.string.quick_settings_title),
                color = colors.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                style = TightLines,
            )
            if (values.date.isNotEmpty()) {
                Text(
                    text = values.date,
                    color = colors.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    style = TightLines,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val shape = RoundedCornerShape(15.dp)
        TvFocusable(
            tag = "qs:launcher_settings",
            scaleOnFocus = 1.05f,
            shape = shape,
            onClick = onLauncherSettings,
        ) { focused ->
            val fg = if (focused) colors.surface else colors.onSurface
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(shape)
                    .background(if (focused) colors.onSurface else colors.onSurface.copy(alpha = 0.10f))
                    .launcherFocusRing(focused, shape)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LauncherGlyph(Glyphs.GEAR, fg, size = 15.dp, stroke = 2f)
                Text(
                    text = stringResource(DsR.string.launcher_settings),
                    color = fg,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Labeled(label: String, colors: LauncherColors, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = colors.onSurfaceVariant,
            fontSize = 11.5.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
        )
        content()
    }
}

@Composable
private fun QsTile(
    tag: String,
    values: QuickSettingsValues,
    colors: LauncherColors,
    height: Dp,
    onAction: (String) -> Unit,
) {
    val (labelRes, glyph, on) = when (tag) {
        "qs:wifi" -> Triple(R.string.qs_wifi, Glyphs.WIFI, values.wifiOn)
        "qs:bt" -> Triple(R.string.qs_bt, Glyphs.BLUETOOTH, values.bluetoothOn)
        "qs:dnd" -> Triple(R.string.qs_dnd, Glyphs.DND, values.dndOn)
        "qs:ambient" -> Triple(R.string.qs_ambient, Glyphs.SCENERY, false)
        "qs:inputs" -> Triple(R.string.qs_inputs, Glyphs.INPUT, false)
        "qs:saver" -> Triple(R.string.qs_saver, Glyphs.MOON, false)
        "qs:parent" -> Triple(R.string.qs_parent, Glyphs.LOCK, false)
        else -> Triple(R.string.qs_transfer, Glyphs.TRANSFER, false)
    }
    val action = tag.removePrefix("qs:")
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.04f,
        shape = RoundedCornerShape(height / 2),
        onClick = { onAction(action) },
    ) { focused ->
        // 关：全圆胶囊；开：primaryContainer，圆角 16（§09）
        val radius by animateDpAsState(
            targetValue = if (on) 16.dp else height / 2,
            animationSpec = launcherFastSpatialSpec(),
            label = "tileRadius",
        )
        val shape = RoundedCornerShape(radius)
        val bg = if (on) colors.primaryContainer else colors.surfaceContainerHigh
        val fg = if (on) colors.onPrimaryContainer else colors.onSurface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(bg)
                .launcherFocusRing(focused, shape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LauncherGlyph(glyph, fg, size = 18.dp, stroke = 2f)
            Text(text = stringResource(labelRes), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 粗轨滑块（M3E）：左右键调节，OK 静音 / 取消静音。 */
@Composable
private fun VolumeSlider(values: QuickSettingsValues, colors: LauncherColors, onToggleMute: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    TvFocusable(
        tag = "qs:volume",
        scaleOnFocus = 1.02f,
        shape = shape,
        onClick = onToggleMute,
    ) { focused ->
        val fraction by animateFloatAsState(
            targetValue = if (values.volumeMax > 0) values.volume.toFloat() / values.volumeMax else 0f,
            animationSpec = launcherFastSpatialSpec(),
            label = "volume",
        )
        val active = if (values.muted) colors.onSurfaceVariant.copy(alpha = 0.55f) else colors.primary
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(shape)
                .launcherFocusRing(focused, shape)
                .padding(horizontal = 4.dp),
        ) {
        // 左段至少放得下喇叭图标，右段至少放得下数值；中间是 4dp 的手柄
        val track = maxWidth - 12.dp
        val minActive = 34.dp
        val minRest = 40.dp
        val activeWidth = minActive + (track - minActive - minRest) * fraction.coerceIn(0f, 1f)
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(activeWidth)
                    .height(28.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(active),
                contentAlignment = Alignment.CenterStart,
            ) {
                LauncherGlyph(
                    path = if (values.muted) Glyphs.VOLUME_OFF else Glyphs.VOLUME,
                    color = if (values.muted) colors.surface else colors.onPrimary,
                    size = 16.dp,
                    stroke = 2f,
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(active),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 14.dp, bottomEnd = 14.dp))
                    .background(active.copy(alpha = 0.22f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = values.volume.toString(),
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        }
    }
}

/** M3E 连接式按钮组：选中段变成全圆角并用 secondaryContainer；焦点框包住整组。 */
@Composable
private fun ConnectedGroup(
    tag: String,
    options: List<String>,
    selected: String,
    label: @Composable (String) -> String,
    colors: LauncherColors,
    onClick: () -> Unit,
) {
    val outer = RoundedCornerShape(20.dp)
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.02f,
        shape = outer,
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .launcherFocusRing(focused, outer),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEachIndexed { i, option ->
                val on = option == selected
                val start by animateDpAsState(
                    targetValue = if (on || i == 0) 18.dp else 8.dp,
                    animationSpec = launcherFastSpatialSpec(),
                    label = "segStart",
                )
                val end by animateDpAsState(
                    targetValue = if (on || i == options.lastIndex) 18.dp else 8.dp,
                    animationSpec = launcherFastSpatialSpec(),
                    label = "segEnd",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                        .background(if (on) colors.secondaryContainer else colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        color = if (on) colors.onSecondaryContainer else colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(value: String): String = stringResource(
    when (value) {
        "icon" -> R.string.qs_icon
        "wallpaper" -> R.string.qs_wallpaper
        "fixed" -> R.string.qs_fixed
        else -> R.string.qs_content
    },
)

@Composable
private fun glassLabel(value: String): String = stringResource(
    when (value) {
        "T1" -> R.string.qs_live
        "T2" -> R.string.qs_frozen
        "T3" -> R.string.qs_solid
        else -> R.string.qs_auto
    },
)

@Composable
private fun styleLabel(value: String): String = stringResource(
    when (value) {
        "content" -> R.string.qs_style_content
        "vibrant" -> R.string.qs_style_vibrant
        "expressive" -> R.string.qs_style_expressive
        else -> R.string.qs_style_tonal
    },
)
