package com.github.mytv.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.mytv.data.weather.Sky
import com.github.mytv.data.weather.isNightVariant

private const val CLOUD =
    "M6.5 19a4.5 4.5 0 0 1-.9-8.91A6 6 0 0 1 17.2 8.6 5.2 5.2 0 0 1 17.5 19z"
private const val MOON = "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"

private val SUN = Color(0xFFF5B43C)
private val MOON_COLOR = Color(0xFFC9D3F2)
private val RAIN = Color(0xFF5EA2F0)
private val SNOW = Color(0xFFE3ECFA)
private val BOLT = Color(0xFFF7D046)

/** Filled 24dp weather shapes from supplement A3. Colors are condition colors. */
@Composable
fun WeatherGlyph(
    sky: Sky,
    code: Int,
    isDay: Boolean,
    cloudColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    highContrast: Boolean = false,
) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        drawGlyph(sky, isNightVariant(code, if (isDay) 1 else 0), cloudColor, highContrast)
    }
}

private fun DrawScope.drawGlyph(sky: Sky, night: Boolean, cloud: Color, highContrast: Boolean) {
    val unit = size.minDimension / 24f
    val sun = if (highContrast) Color(0xFFFFC766) else SUN
    when (sky) {
        Sky.Clear -> if (night) path(MOON, MOON_COLOR, unit) else {
            drawCircle(sun, radius = 4.6f * unit, center = Offset(12 * unit, 12 * unit))
            val stroke = 2f * unit
            for (i in 0 until 8) {
                val angle = Math.toRadians((i * 45).toDouble())
                val sx = 12f + kotlin.math.cos(angle).toFloat() * 9.4f
                val sy = 12f + kotlin.math.sin(angle).toFloat() * 9.4f
                val ex = 12f + kotlin.math.cos(angle).toFloat() * 7.2f
                val ey = 12f + kotlin.math.sin(angle).toFloat() * 7.2f
                drawLine(sun, Offset(sx * unit, sy * unit), Offset(ex * unit, ey * unit), stroke, StrokeCap.Round)
            }
        }
        Sky.Partly -> {
            if (night) path(MOON, MOON_COLOR, unit, dx = -0.6f, dy = -0.6f, scale = 0.56f)
            else {
                drawCircle(sun, radius = 3.4f * unit, center = Offset(8.5f * unit, 8.5f * unit))
                val stroke = 1.8f * unit
                drawLine(sun, Offset(1.6f * unit, 8.5f * unit), Offset(3.3f * unit, 8.5f * unit), stroke, StrokeCap.Round)
                drawLine(sun, Offset(8.5f * unit, 1.6f * unit), Offset(8.5f * unit, 3.3f * unit), stroke, StrokeCap.Round)
                drawLine(sun, Offset(3.6f * unit, 3.6f * unit), Offset(4.8f * unit, 4.8f * unit), stroke, StrokeCap.Round)
                drawLine(sun, Offset(13.4f * unit, 3.6f * unit), Offset(12.2f * unit, 4.8f * unit), stroke, StrokeCap.Round)
                drawLine(sun, Offset(3.6f * unit, 13.4f * unit), Offset(4.8f * unit, 12.2f * unit), stroke, StrokeCap.Round)
            }
            path(CLOUD, cloud, unit, dx = 5.5f, dy = 6f, scale = 0.74f)
        }
        Sky.Overcast -> path(CLOUD, cloud, unit, dy = 0.5f)
        Sky.Fog -> {
            val stroke = 2.3f * unit
            val lines = listOf(
                4f to 6.5f to 16f, 7f to 10.5f to 20f, 3.5f to 14.5f to 16.5f, 7.5f to 18.5f to 18.5f,
            )
            for (line in lines) {
                val y = line.first.first
                val x1 = line.first.second
                val x2 = line.second
                drawLine(cloud, Offset(x1 * unit, y * unit), Offset(x2 * unit, y * unit), stroke, StrokeCap.Round)
            }
        }
        Sky.Rain -> {
            path(CLOUD, cloud, unit, dx = 1.6f, dy = -2.2f, scale = 0.86f)
            val stroke = 2.2f * unit
            for (x in listOf(8.3f, 12.8f, 17.3f)) {
                drawLine(RAIN, Offset(x * unit, 17.2f * unit), Offset((x - 1.3f) * unit, 20.6f * unit), stroke, StrokeCap.Round)
            }
        }
        Sky.Snow -> {
            path(CLOUD, cloud, unit, dx = 1.6f, dy = -2.2f, scale = 0.86f)
            for (point in listOf(
                7.4f to 17.4f, 12f to 17.4f, 16.6f to 17.4f, 9.7f to 21.2f, 14.3f to 21.2f,
            )) {
                drawCircle(SNOW, 1.3f * unit, Offset(point.first * unit, point.second * unit))
            }
        }
        Sky.Thunder -> {
            path(CLOUD, cloud, unit, dx = 1.6f, dy = -2.2f, scale = 0.86f)
            path(
                "M12.9 13.2 9.4 18.6h2.9l-1.1 4.6 4.5-6.6h-3l1.4-3.4z",
                BOLT,
                unit,
            )
        }
    }
}

private fun DrawScope.path(
    data: String,
    color: Color,
    unit: Float,
    dx: Float = 0f,
    dy: Float = 0f,
    scale: Float = 1f,
) {
    val path: Path = PathParser().parsePathString(data).toPath()
    withTransform({
        translate(dx * unit, dy * unit)
        scale(scale * unit, scale * unit, pivot = Offset.Zero)
    }) {
        drawPath(path, color)
    }
}
