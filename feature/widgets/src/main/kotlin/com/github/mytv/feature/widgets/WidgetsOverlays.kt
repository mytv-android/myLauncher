package com.github.mytv.feature.widgets

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.launcherDefaultSpatialSpec
import com.github.mytv.core.designsystem.launcherFastSpatialSpec
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.glass.GlassStyle
import com.github.mytv.core.glass.GlassSurface
import com.github.mytv.core.glass.GlassTier
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.widgets.BuiltinWidgets
import com.github.mytv.data.widgets.WidgetsRepository
import dev.chrisbanes.haze.HazeInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.github.mytv.data.widgets.R as WidgetsDataR

/**
 * 小组件页的浮层：选择器、首次授权、电脑授权与长按菜单。宿主（app）把它画在顶栏之上，
 * 选择器与原型一样是右侧的玻璃面板（§12）。
 */
@Composable
fun WidgetsOverlayLayer(
    repository: WidgetsRepository,
    host: WidgetsHost,
    tier: GlassTier,
    colors: LauncherColors,
    input: HazeInput?,
    clockText: String,
    modifier: Modifier = Modifier,
) {
    val ui by host.ui.collectAsState()
    val providers by repository.installedProviders.collectAsState()
    when (ui.overlay) {
        WidgetOverlay.PICKER -> SidePanel(tier, colors, input, host::dismissOverlay, "picker", modifier) {
            PanelHeader(stringResource(R.string.widgets_add), colors)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PickerRow(
                    tag = "picker:builtin:clock",
                    colors = colors,
                    name = stringResource(WidgetsDataR.string.builtin_clock_title),
                    sub = "${appLabel(LocalContext.current, LocalContext.current.packageName)} · 2×1",
                    preview = { ClockPreview(clockText) },
                    onClick = { host.selectProvider(BuiltinWidgets.CLOCK) },
                )
                providers.forEach { info ->
                    val tag = repository.providerTag(info)
                    val span = remember(info) { repository.defaultSpan(info) }
                    PickerRow(
                        tag = "picker:$tag",
                        colors = colors,
                        name = repository.providerLabel(info),
                        sub = "${appLabel(LocalContext.current, info.provider.packageName)} · ${span.first}×${span.second}",
                        preview = { ProviderPreview(info) },
                        onClick = { host.selectProvider(tag) },
                    )
                }
            }
        }

        WidgetOverlay.AUTH -> SidePanel(tier, colors, input, host::authCancel, "picker", modifier) {
            PanelHeader(stringResource(R.string.widgets_add), colors)
            Note(stringResource(R.string.widget_auth_title), stringResource(R.string.widget_auth_body), colors)
            SoloRow("picker:auth:adb", stringResource(R.string.widget_auth_adb), colors, host::authShowAdb)
            SoloRow("picker:auth:cancel", stringResource(R.string.cancel), colors, host::authCancel)
        }

        WidgetOverlay.ADB -> SidePanel(tier, colors, input, host::dismissOverlay, "picker", modifier) {
            PanelHeader(stringResource(R.string.widgets_add), colors)
            Note(stringResource(R.string.widget_auth_adb), stringResource(R.string.widget_auth_adb_body), colors) {
                Text(
                    text = stringResource(R.string.widget_auth_command),
                    color = Color(0xFFE6E6EA),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.38f))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                )
            }
            SoloRow("picker:adb:done", stringResource(R.string.widget_auth_done), colors, host::authDone)
            SoloRow("picker:adb:back", stringResource(R.string.back), colors, host::authBack)
        }

        WidgetOverlay.MENU -> {
            val id = ui.menuBindingId
            if (id != null) {
                MenuPanel(
                    actions = host.menuActions(id),
                    tier = tier,
                    colors = colors,
                    input = input,
                    onAction = host::onMenuAction,
                    onDismiss = host::dismissOverlay,
                    modifier = modifier,
                )
            }
        }

        WidgetOverlay.NONE -> Unit
    }
}

/** 右侧玻璃面板：top / right / bottom 14，宽 384，圆角 30，从右侧滑入（原型 .tv-pk）。 */
@Composable
private fun SidePanel(
    tier: GlassTier,
    colors: LauncherColors,
    input: HazeInput?,
    onDismiss: () -> Unit,
    testTag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val slide = remember { Animatable(1f) }
    val spec = launcherDefaultSpatialSpec<Float>()
    LaunchedEffect(Unit) { slide.animateTo(0f, spec) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x61000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
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
                .width(384.dp)
                .graphicsLayer { translationX = slide.value * 420.dp.toPx() }
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 18.dp, vertical = 20.dp)
                    .testTag(testTag),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PanelHeader(text: String, colors: LauncherColors) {
    Text(
        text = text,
        color = colors.onSurface,
        fontSize = 19.sp,
        fontWeight = FontWeight.ExtraBold,
    )
}

@Composable
private fun Note(
    title: String,
    body: String,
    colors: LauncherColors,
    extra: @Composable () -> Unit = {},
) {
    Column {
        Text(
            text = title,
            color = colors.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = body,
            color = colors.onSurfaceVariant,
            fontSize = 12.5.sp,
            lineHeight = 20.sp,
        )
        extra()
    }
}

/** 选择器条目：左侧 92dp 预览，右侧名称与「应用 · 尺寸」；聚焦时反色、圆角 18 → 12。 */
@Composable
private fun PickerRow(
    tag: String,
    colors: LauncherColors,
    name: String,
    sub: String,
    preview: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.03f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) { focused ->
        val radius by animateDpAsState(if (focused) 12.dp else 18.dp, launcherFastSpatialSpec(), label = "pkRadius")
        val shape = RoundedCornerShape(radius)
        val fg = if (focused) colors.surface else colors.onSurface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(shape)
                .background(if (focused) colors.onSurface else colors.surfaceContainerHigh.copy(alpha = 0.72f))
                .launcherFocusRing(focused, shape)
                .padding(start = 6.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(lerp(Color(0xFFF4F6F9), colors.primary, 0.26f)),
                contentAlignment = Alignment.Center,
            ) {
                preview()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = fg,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sub,
                    color = fg.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SoloRow(tag: String, label: String, colors: LauncherColors, onClick: () -> Unit) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.03f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) { focused ->
        val radius by animateDpAsState(if (focused) 12.dp else 18.dp, launcherFastSpatialSpec(), label = "soloRadius")
        val shape = RoundedCornerShape(radius)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (focused) colors.onSurface else colors.surfaceContainerHigh.copy(alpha = 0.72f))
                .launcherFocusRing(focused, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = label,
                color = if (focused) colors.surface else colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ClockPreview(clockText: String) {
    val text = clockText.ifEmpty {
        remember { SimpleDateFormat("HH:mm", Locale.US).format(Date()) }
    }
    Text(
        text = text,
        color = Color(0xFF1A1C1E),
        fontSize = 17.sp,
        fontWeight = FontWeight.Light,
    )
}

/** provider 自带的预览图；没有时用应用图标。 */
@Composable
private fun ProviderPreview(info: AppWidgetProviderInfo) {
    val context = LocalContext.current
    val image by produceState<Pair<ImageBitmap, Boolean>?>(null, info) {
        value = withContext(Dispatchers.IO) {
            val density = context.resources.displayMetrics.densityDpi
            val preview = runCatching { info.loadPreviewImage(context, density) }.getOrNull()
            val drawable = preview ?: runCatching { info.loadIcon(context, density) }.getOrNull()
            drawable?.let { it.toImageBitmap(256)?.let { bmp -> bmp to (preview != null) } }
        }
    }
    val current = image ?: return
    if (current.second) {
        Image(
            bitmap = current.first,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
        )
    } else {
        Image(
            bitmap = current.first,
            contentDescription = null,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape),
        )
    }
}

private fun Drawable.toImageBitmap(maxPx: Int): ImageBitmap? {
    if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
    val w = intrinsicWidth.takeIf { it > 0 } ?: maxPx
    val h = intrinsicHeight.takeIf { it > 0 } ?: maxPx
    val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
    val bw = (w * scale).toInt().coerceAtLeast(1)
    val bh = (h * scale).toInt().coerceAtLeast(1)
    return runCatching {
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, bw, bh)
        draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

private fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Exception) {
    packageName
}

/** 长按菜单：靠右的玻璃小面板。 */
@Composable
private fun MenuPanel(
    actions: List<WidgetMenuAction>,
    tier: GlassTier,
    colors: LauncherColors,
    input: HazeInput?,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x61000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        GlassSurface(
            tier = tier,
            colors = colors,
            input = input,
            style = GlassStyle.Panel,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 64.dp)
                .width(300.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .testTag("menu"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                actions.forEach { action ->
                    SoloRow(action.tag, stringResource(action.labelRes), colors) { onAction(action.tag) }
                }
            }
        }
    }
}
