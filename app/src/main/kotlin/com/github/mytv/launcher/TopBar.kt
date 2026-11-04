package com.github.mytv.launcher

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.ambient.BackdropSource
import com.github.mytv.core.designsystem.LauncherColors
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
    modifier: Modifier = Modifier,
    dateText: String = "",
    lunarText: String = "",
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
        GlassSurface(
            tier = tier,
            colors = colors,
            input = hazeInput,
            style = GlassStyle.Chrome,
            shape = RoundedCornerShape(23.dp),
            chromeAlpha = chromeAlpha,
        ) {
            Row(
                modifier = Modifier.height(46.dp).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleIcon(
                    tag = "nav:search",
                    enabled = enabled,
                    colors = colors,
                    onClick = {},
                    draw = { drawSearch(it) },
                )
                CapsuleIcon(
                    tag = "nav:ambient",
                    enabled = enabled,
                    colors = colors,
                    onClick = {},
                    draw = { drawAmbient(it) },
                )
                CapsuleIcon(
                    tag = "nav:settings",
                    enabled = enabled,
                    colors = colors,
                    onClick = onSettings,
                    draw = { drawSettings(it) },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusPlate(
            tier = tier,
            colors = colors,
            hazeInput = hazeInput,
            chromeAlpha = chromeAlpha,
            dateText = dateText,
            lunarText = lunarText,
            clockText = clockText,
            weatherVisible = weatherVisible,
            weatherTempC = weatherTempC,
            weatherCode = weatherCode,
            weatherIsDay = weatherIsDay,
            highContrast = highContrast,
        )
    }
}

@Composable
private fun StatusPlate(
    tier: GlassTier,
    colors: LauncherColors,
    hazeInput: HazeInput?,
    chromeAlpha: Float,
    dateText: String,
    lunarText: String,
    clockText: String,
    weatherVisible: Boolean,
    weatherTempC: Int?,
    weatherCode: Int?,
    weatherIsDay: Boolean,
    highContrast: Boolean,
) {
    val look = weatherCode?.let { lookOf(it) }
    val showWeather = weatherVisible && weatherTempC != null
    val temperature = weatherTempC?.let { "$it°" }
    val label = look?.let { stringResource(it.label) }
    GlassSurface(
        tier = tier,
        colors = colors,
        input = hazeInput,
        style = GlassStyle.Chrome,
        shape = RoundedCornerShape(23.dp),
        chromeAlpha = chromeAlpha,
    ) {
        Row(
            modifier = Modifier
                .height(46.dp)
                .padding(start = if (showWeather) 14.dp else 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showWeather && temperature != null) {
                Row(
                    modifier = Modifier.semantics {
                        contentDescription = listOfNotNull(label, temperature).joinToString(" ")
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (look != null) {
                        WeatherGlyph(
                            sky = look.sky,
                            code = weatherCode ?: 0,
                            isDay = weatherIsDay,
                            cloudColor = colors.onSurfaceVariant,
                            size = 24.dp,
                            highContrast = highContrast,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = temperature,
                        color = colors.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .width(1.dp)
                        .height(18.dp)
                        .background(colors.onSurface.copy(alpha = 0.24f)),
                )
            }
            val grouped = if (lunarText.isBlank()) dateText else "$dateText · $lunarText"
            Text(
                text = grouped,
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = clockText,
                color = colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("clock"),
            )
        }
    }
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
    draw: DrawScope.(Color) -> Unit,
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
            Canvas(modifier = Modifier.size(20.dp)) {
                draw(if (focused) colors.surface else colors.onSurface)
            }
        }
    }
}

private fun DrawScope.drawSearch(color: Color) {
    val stroke = 1.8.dp.toPx()
    drawCircle(
        color = color,
        radius = size.minDimension * 0.30f,
        center = Offset(size.width * 0.43f, size.height * 0.43f),
        style = Stroke(width = stroke),
    )
    drawLine(
        color = color,
        start = Offset(size.width * 0.64f, size.height * 0.64f),
        end = Offset(size.width * 0.86f, size.height * 0.86f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawAmbient(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = size.minDimension * 0.44f
    val inner = size.minDimension * 0.12f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx, cy - outer)
        quadraticTo(cx + inner, cy - inner, cx + outer, cy)
        quadraticTo(cx + inner, cy + inner, cx, cy + outer)
        quadraticTo(cx - inner, cy + inner, cx - outer, cy)
        quadraticTo(cx - inner, cy - inner, cx, cy - outer)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawSettings(color: Color) {
    val stroke = 1.8.dp.toPx()
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = color, radius = size.minDimension * 0.30f, center = center, style = Stroke(width = stroke))
    drawCircle(color = color, radius = size.minDimension * 0.10f, center = center)
    for (i in 0 until 6) {
        val angle = Math.toRadians(i * 60.0)
        val sx = center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.30f
        val sy = center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.30f
        val ex = center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.44f
        val ey = center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.44f
        drawLine(color, Offset(sx, sy), Offset(ex, ey), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
