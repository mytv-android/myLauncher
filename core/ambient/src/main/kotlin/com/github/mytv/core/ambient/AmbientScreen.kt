package com.github.mytv.core.ambient

import android.text.format.DateFormat
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen ambient/screensaver surface.
 *
 * The caller owns the player through [AmbientPlaybackController] and passes
 * `onSurfaceView` so the controller can attach the Media3 output.
 * `onExit` is invoked by any key; when `onPreviousClip`/`onNextClip` are
 * supplied, LEFT/RIGHT switch clips instead (screensaver behaviour).
 *
 * Typical host wiring:
 * ```
 * val base by controller.state.collectAsState()
 * AmbientScreen(
 *     state = base.copy(
 *         weather = weatherFromDataWeather,
 *         clock = AmbientClock(is24h = clockFormat.is24h),
 *         lunarText = lunarFromDataWeather,
 *     ),
 *     onSurfaceView = { view -> if (view != null) controller.attachSurface(view) else controller.detachSurface() },
 *     onExit = { exitAmbient() },
 * )
 * ```
 */
@Composable
fun AmbientScreen(
    state: AmbientState,
    modifier: Modifier = Modifier,
    onSurfaceView: (SurfaceView?) -> Unit = {},
    onExit: () -> Unit = {},
    onPreviousClip: (() -> Unit)? = null,
    onNextClip: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val nowMillis = rememberNowMillis()
    val context = LocalContext.current
    val tempText = state.weather?.let {
        context.getString(R.string.ambient_temp, it.tempC)
    }
    val clockText = remember(nowMillis / 60_000, state.clock.is24h) {
        formatClock(nowMillis, state.clock.is24h)
    }
    val dateText = remember(nowMillis / 60_000, state.lunarText) {
        formatDate(nowMillis, state.lunarText)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionLeft && onPreviousClip != null -> {
                        onPreviousClip()
                        true
                    }
                    event.key == Key.DirectionRight && onNextClip != null -> {
                        onNextClip()
                        true
                    }
                    else -> {
                        onExit()
                        true
                    }
                }
            },
    ) {
        AmbientBackdrop(BackdropSource.None, Modifier.fillMaxSize())
        AndroidView(
            factory = { viewContext ->
                AmbientSurfaceView(viewContext).apply {
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }.also(onSurfaceView)
            },
            modifier = Modifier.fillMaxSize(),
            update = onSurfaceView,
        )
        DisposableEffect(Unit) {
            onDispose { onSurfaceView(null) }
        }
        val still = state.stillFrame
        if (still != null) {
            AndroidView(
                factory = { viewContext ->
                    android.widget.ImageView(viewContext).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view -> view.setImageBitmap(still.asAndroidBitmap()) },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.42f),
                    ),
                ),
        )
        if (state.dimmed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = state.drift.xDp.dp, y = state.drift.yDp.dp)
                .padding(end = 48.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (state.weather != null && tempText != null) {
                    WeatherUnit(state.weather, tempText)
                    Spacer(Modifier.width(28.dp))
                }
                Text(
                    text = clockText,
                    modifier = Modifier.testTag("ambient:clock"),
                    style = ambientTextStyle(size = 72.sp, weight = 250, monospace = true),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = dateText,
                modifier = Modifier.testTag("ambient:date"),
                style = ambientTextStyle(size = 20.sp, weight = 400),
            )
            if (state.showInfo && state.clip != null) {
                val place = state.clip.location.ifBlank { state.clip.license }
                val info = listOf(state.clip.title, place)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (info.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = info,
                        modifier = Modifier.testTag("ambient:info"),
                        style = ambientTextStyle(size = 14.sp, weight = 400),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherUnit(weather: AmbientWeather, tempText: String) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.semantics {
            contentDescription = weather.condition + " " + tempText
        },
    ) {
        WeatherGlyph(weather.sky, Modifier.size(44.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = tempText,
            modifier = Modifier.width(115.dp),
            style = ambientTextStyle(size = 44.sp, weight = 300, monospace = true),
        )
    }
}

private fun ambientTextStyle(size: androidx.compose.ui.unit.TextUnit, weight: Int, monospace: Boolean = false) =
    TextStyle(
        color = Color.White,
        fontSize = size,
        fontWeight = FontWeight(weight),
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        shadow = Shadow(Color(0x59000000), Offset(0f, 2f), 30f),
    )

@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun formatClock(millis: Long, is24h: Boolean): String {
    val pattern = if (is24h) "HH:mm" else "ah:mm"
    return SimpleDateFormat(pattern, Locale.SIMPLIFIED_CHINESE).format(Date(millis))
}

private fun formatDate(millis: Long, lunarText: String?): String {
    val locale = Locale.SIMPLIFIED_CHINESE
    val datePattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
    val date = SimpleDateFormat(datePattern, locale).format(Date(millis))
    val weekday = SimpleDateFormat("EEE", locale).format(Date(millis))
    val base = "$date $weekday"
    return if (lunarText.isNullOrBlank()) base else "$base · $lunarText"
}

@Composable
private fun WeatherGlyph(sky: AmbientSky, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (sky) {
            AmbientSky.CLEAR -> drawSun(w, h)
            AmbientSky.PARTLY -> {
                drawSun(w * 0.62f, h * 0.56f, radius = w * 0.18f)
                drawCloud(w * 0.46f, h * 0.62f, w * 0.48f)
            }
            AmbientSky.OVERCAST -> {
                drawCloud(w * 0.42f, h * 0.52f, w * 0.56f)
                drawCloud(w * 0.58f, h * 0.68f, w * 0.44f)
            }
            AmbientSky.FOG -> drawFog(w, h)
            AmbientSky.RAIN -> {
                drawCloud(w * 0.5f, h * 0.48f, w * 0.62f)
                drawRain(w, h)
            }
            AmbientSky.SNOW -> {
                drawCloud(w * 0.5f, h * 0.48f, w * 0.62f)
                drawSnow(w, h)
            }
            AmbientSky.THUNDER -> {
                drawCloud(w * 0.5f, h * 0.46f, w * 0.62f)
                drawThunder(w, h)
            }
        }
    }
}

private fun DrawScope.drawSun(cx: Float, cy: Float, radius: Float = size.minDimension * 0.24f) {
    drawCircle(Color(0xFFFFD166), radius = radius, center = Offset(cx, cy))
    val step = 360f / 8f
    for (index in 0 until 8) {
        val radians = Math.toRadians((index * step).toDouble())
        val inner = radius * 1.35f
        val outer = radius * 1.85f
        drawLine(
            color = Color(0xFFFFD166),
            start = Offset(cx + (kotlin.math.cos(radians) * inner).toFloat(), cy + (kotlin.math.sin(radians) * inner).toFloat()),
            end = Offset(cx + (kotlin.math.cos(radians) * outer).toFloat(), cy + (kotlin.math.sin(radians) * outer).toFloat()),
            strokeWidth = size.minDimension * 0.05f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawCloud(cx: Float, cy: Float, width: Float) {
    val paintColor = Color(0xFFE9EEF5)
    val height = width * 0.58f
    drawCircle(paintColor, radius = height * 0.42f, center = Offset(cx - width * 0.22f, cy))
    drawCircle(paintColor, radius = height * 0.55f, center = Offset(cx, cy - height * 0.16f))
    drawCircle(paintColor, radius = height * 0.38f, center = Offset(cx + width * 0.24f, cy))
    drawRoundRect(
        color = paintColor,
        topLeft = Offset(cx - width * 0.48f, cy),
        size = androidx.compose.ui.geometry.Size(width * 0.96f, height * 0.48f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height * 0.24f),
    )
}

private fun DrawScope.drawFog(width: Float, height: Float) {
    for (index in 0 until 3) {
        val y = height * (0.34f + index * 0.16f)
        drawLine(
            color = Color(0xFFD6DEE8),
            start = Offset(width * 0.14f, y),
            end = Offset(width * (0.86f - index * 0.06f), y),
            strokeWidth = height * 0.07f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawRain(width: Float, height: Float) {
    for (index in 0 until 3) {
        val x = width * (0.28f + index * 0.22f)
        drawLine(
            color = Color(0xFF8ECAE6),
            start = Offset(x, height * 0.66f),
            end = Offset(x - width * 0.08f, height * 0.9f),
            strokeWidth = width * 0.055f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawSnow(width: Float, height: Float) {
    for (index in 0 until 3) {
        drawCircle(
            color = Color(0xFFE9EEF5),
            radius = width * 0.05f,
            center = Offset(width * (0.3f + index * 0.2f), height * (0.76f + (index % 2) * 0.1f)),
        )
    }
}

private fun DrawScope.drawThunder(width: Float, height: Float) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(width * 0.54f, height * 0.66f)
        lineTo(width * 0.44f, height * 0.86f)
        lineTo(width * 0.55f, height * 0.84f)
        lineTo(width * 0.47f, height * 1.02f)
        lineTo(width * 0.66f, height * 0.78f)
        lineTo(width * 0.55f, height * 0.8f)
        close()
    }
    drawPath(path, Color(0xFFFFD166))
    drawPath(path, Color(0x33FFFFFF), style = Stroke(width = width * 0.01f))
}

/** SurfaceView 的可访问性类名默认是 android.view.View，这里保留真实类名便于检查识别。 */
private class AmbientSurfaceView(context: android.content.Context) : SurfaceView(context) {
    override fun getAccessibilityClassName(): CharSequence = "android.view.SurfaceView"
}
