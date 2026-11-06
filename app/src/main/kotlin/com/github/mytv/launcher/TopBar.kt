package com.github.mytv.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.ambient.BackdropSource
import com.github.mytv.core.designsystem.Glyphs
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.LauncherGlyph
import com.github.mytv.core.designsystem.LocalLauncherColors
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.designsystem.rememberArtwork
import com.github.mytv.core.glass.GlassStyle
import com.github.mytv.core.glass.GlassSurface
import com.github.mytv.core.glass.GlassTier
import com.github.mytv.core.glass.plateAlpha
import com.github.mytv.core.glass.relativeLuminance
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.weather.lookOf
import dev.chrisbanes.haze.HazeInput
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun TopBar(
    page: String,
    overlayOpen: Boolean,
    clockText: String,
    tier: GlassTier,
    hazeInput: HazeInput?,
    onTab: (String) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onAmbient: () -> Unit,
    modifier: Modifier = Modifier,
    weatherVisible: Boolean = false,
    weatherTempC: Int? = null,
    weatherCode: Int? = null,
    weatherIsDay: Boolean = true,
    backdrop: BackdropSource = BackdropSource.None,
    highContrast: Boolean = false,
) {
    val enabled = !overlayOpen
    val colors = LocalLauncherColors.current.value
    val brightest = brightestUnderPlate(backdrop, colors.surfaceContainer)
    val chromeAlpha = plateAlpha(brightest, colors.surfaceContainer, colors.onSurfaceVariant)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = RowLeft, end = RowLeft, top = 22.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassSurface(
            tier = tier,
            colors = colors,
            input = hazeInput,
            style = GlassStyle.Chrome,
            shape = RoundedCornerShape(23.dp),
        ) {
            Row(
                modifier = Modifier.height(46.dp).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavTab(
                    tag = "nav:home",
                    label = stringResource(DsR.string.nav_home),
                    selected = page == "home",
                    enabled = enabled,
                    colors = colors,
                    onClick = { onTab("home") },
                )
                NavTab(
                    tag = "nav:apps",
                    label = stringResource(DsR.string.nav_apps),
                    selected = page == "apps",
                    enabled = enabled,
                    colors = colors,
                    onClick = { onTab("apps") },
                )
                NavTab(
                    tag = "nav:widgets",
                    label = stringResource(DsR.string.nav_widgets),
                    selected = page == "widgets",
                    enabled = enabled,
                    colors = colors,
                    onClick = { onTab("widgets") },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 工具与状态合成一块玻璃（用户选定的顶栏方案 A）：搜索、环境模式、快速设置，
        // 分隔线后是天气与时钟；状态部分不可聚焦。日期与农历在快速设置的标题行。
        GlassSurface(
            tier = tier,
            colors = colors,
            input = hazeInput,
            style = GlassStyle.Chrome,
            shape = RoundedCornerShape(23.dp),
            chromeAlpha = chromeAlpha,
        ) {
            Row(
                modifier = Modifier.height(46.dp).padding(start = 6.dp, end = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleIcon(
                    tag = "nav:search",
                    enabled = enabled,
                    colors = colors,
                    onClick = onSearch,
                    glyph = Glyphs.SEARCH,
                )
                CapsuleIcon(
                    tag = "nav:ambient",
                    enabled = enabled,
                    colors = colors,
                    onClick = onAmbient,
                    glyph = Glyphs.AMBIENT,
                )
                CapsuleIcon(
                    tag = "nav:settings",
                    enabled = enabled,
                    colors = colors,
                    onClick = onSettings,
                    glyph = Glyphs.GEAR,
                )
                StatusInline(
                    colors = colors,
                    clockText = clockText,
                    weatherVisible = weatherVisible,
                    weatherTempC = weatherTempC,
                    weatherCode = weatherCode,
                    weatherIsDay = weatherIsDay,
                    highContrast = highContrast,
                )
            }
        }
    }
}

private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun StatusInline(
    colors: LauncherColors,
    clockText: String,
    weatherVisible: Boolean,
    weatherTempC: Int?,
    weatherCode: Int?,
    weatherIsDay: Boolean,
    highContrast: Boolean,
) {
    val look = weatherCode?.let { lookOf(it) }
    val temperature = weatherTempC?.takeIf { weatherVisible }?.let { "$it°" }
    val label = look?.let { stringResource(it.label) }
    Box(
        modifier = Modifier
            .padding(start = 8.dp, end = 12.dp)
            .width(1.dp)
            .height(18.dp)
            .background(colors.onSurface.copy(alpha = 0.24f)),
    )
    if (temperature != null) {
        Row(
            modifier = Modifier
                .padding(end = 12.dp)
                .semantics { contentDescription = listOfNotNull(label, temperature).joinToString(" ") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (look != null) {
                WeatherGlyph(
                    sky = look.sky,
                    code = weatherCode ?: 0,
                    isDay = weatherIsDay,
                    cloudColor = colors.onSurfaceVariant,
                    size = 22.dp,
                    highContrast = highContrast,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = temperature,
                color = colors.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                style = TabularNumbers,
            )
        }
    }
    Text(
        text = clockText,
        color = colors.onSurface,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        style = TabularNumbers,
        modifier = Modifier.testTag("clock"),
    )
}

@Composable
private fun brightestUnderPlate(source: BackdropSource, fallback: Color): Color {
    val art = (source as? BackdropSource.Artwork)?.let { rememberArtwork(it.uri, 480, 270) }
    return remember(source, art, fallback) {
        when (source) {
            is BackdropSource.None -> fallback
            is BackdropSource.Solid -> Color(source.argb)
            is BackdropSource.Checker -> {
                val first = Color(source.first)
                val second = Color(source.second)
                if (relativeLuminance(first.toArgb()) >= relativeLuminance(second.toArgb())) first else second
            }
            is BackdropSource.Artwork -> {
                if (art == null) {
                    fallback
                } else {
                    val pixels = art.toPixelMap()
                    val x0 = (pixels.width * 0.62f).toInt().coerceIn(0, pixels.width - 1)
                    val x1 = (pixels.width - 1).coerceAtLeast(x0)
                    val y0 = 0
                    val y1 = (pixels.height * 0.16f).toInt().coerceIn(0, pixels.height - 1)
                    var best = fallback
                    var bestLum = -1.0
                    val cols = 4
                    val rows = 2
                    for (cy in 0 until rows) {
                        for (cx in 0 until cols) {
                            val sx0 = x0 + (x1 - x0) * cx / cols
                            val sx1 = x0 + (x1 - x0) * (cx + 1) / cols
                            val sy0 = y0 + (y1 - y0) * cy / rows
                            val sy1 = y0 + (y1 - y0) * (cy + 1) / rows
                            var r = 0L
                            var g = 0L
                            var b = 0L
                            var n = 0
                            for (y in sy0 until sy1.coerceAtLeast(sy0 + 1)) {
                                for (x in sx0 until sx1.coerceAtLeast(sx0 + 1)) {
                                    val c = pixels[x, y]
                                    r += (c.red * 255).toLong()
                                    g += (c.green * 255).toLong()
                                    b += (c.blue * 255).toLong()
                                    n++
                                }
                            }
                            if (n == 0) continue
                            val color = Color(
                                red = (r / n).toInt(),
                                green = (g / n).toInt(),
                                blue = (b / n).toInt(),
                            )
                            val lum = relativeLuminance(color.toArgb())
                            if (lum > bestLum) {
                                bestLum = lum
                                best = color
                            }
                        }
                    }
                    best
                }
            }
        }
    }
}

@Composable
private fun NavTab(
    tag: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    colors: LauncherColors,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(19.dp)
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.07f,
        shape = shape,
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(shape)
                .background(
                    when {
                        focused -> colors.onSurface
                        selected -> colors.onSurface.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    },
                )
                .launcherFocusRing(focused, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) colors.surface else colors.onSurface,
                fontSize = 14.5.sp,
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CapsuleIcon(
    tag: String,
    enabled: Boolean,
    colors: LauncherColors,
    onClick: () -> Unit,
    glyph: String,
) {
    val shape = RoundedCornerShape(12.dp)
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.1f,
        shape = shape,
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(shape)
                .background(if (focused) colors.onSurface else Color.Transparent)
                .launcherFocusRing(focused, shape)
                .padding(9.dp),
            contentAlignment = Alignment.Center,
        ) {
            LauncherGlyph(
                path = glyph,
                color = if (focused) colors.surface else colors.onSurface,
                size = 20.dp,
                stroke = 2f,
            )
        }
    }
}

