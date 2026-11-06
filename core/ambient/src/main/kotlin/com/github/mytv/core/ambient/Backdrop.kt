package com.github.mytv.core.ambient

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.github.mytv.core.designsystem.LocalLauncherColors
import com.github.mytv.core.designsystem.rememberArtwork
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface BackdropSource {
    data object None : BackdropSource

    data class Artwork(val uri: String) : BackdropSource

    data class Solid(val argb: Int) : BackdropSource

    data class Checker(val cellPx: Int, val first: Int, val second: Int) : BackdropSource
}

object BackdropOverride {
    val state = MutableStateFlow<BackdropSource?>(null)

    fun set(source: BackdropSource?) {
        state.value = source
    }
}

/**
 * 背景层与两层遮罩（原型 `.tv-scrim` / `.tv-scrim2`）：
 * - 常驻：左侧主色 tone 12 压暗（92% → 66% → 0），底部 surface 渐变；
 * - [browse] 时再叠一层自下而上的 surface（100% → 88% → 30%），内容行更易读。
 */
@Composable
fun AmbientBackdrop(
    source: BackdropSource,
    modifier: Modifier = Modifier,
    crossfadeMillis: Int = 900,
    browse: Boolean = false,
) {
    val colors = LocalLauncherColors.current
    val browseAlpha by animateFloatAsState(
        targetValue = if (browse) 1f else 0f,
        animationSpec = tween(600),
        label = "browseScrim",
    )
    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(
            targetState = source,
            animationSpec = tween(crossfadeMillis),
            label = "backdrop",
        ) { target ->
            BackdropLayer(target)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val dynamic = colors.value
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to dynamic.ambient.copy(alpha = 0.92f),
                            0.36f to dynamic.ambient.copy(alpha = 0.66f),
                            0.72f to Color.Transparent,
                        ),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.36f to Color.Transparent,
                            0.70f to dynamic.surface.copy(alpha = 0.78f),
                            1f to dynamic.surface,
                        ),
                    )
                    if (browseAlpha > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to dynamic.surface.copy(alpha = 0.30f),
                                0.48f to dynamic.surface.copy(alpha = 0.88f),
                                1f to dynamic.surface,
                            ),
                            alpha = browseAlpha,
                        )
                    }
                },
        )
    }
}

@Composable
private fun BackdropLayer(source: BackdropSource) {
    when (source) {
        BackdropSource.None -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF15141B), Color(0xFF0D0C10), Color(0xFF1B1722)),
                    ),
                ),
        )
        is BackdropSource.Artwork -> {
            val art = rememberArtwork(source.uri, 960, 540)
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF141218)))
            }
        }
        is BackdropSource.Solid -> Box(
            modifier = Modifier.fillMaxSize().background(Color(source.argb)),
        )
        is BackdropSource.Checker -> {
            val brush = remember(source.cellPx, source.first, source.second) {
                checkerBrush(source.cellPx, Color(source.first), Color(source.second))
            }
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = brush)
            }
        }
    }
}

fun checkerBrush(cellPx: Int, first: Color, second: Color): Brush {
    val cell = cellPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(first.toArgb())
    val paint = Paint().apply { color = second.toArgb() }
    canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
    canvas.drawRect(cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), (cell * 2).toFloat(), paint)
    val image: ImageBitmap = bitmap.asImageBitmap()
    return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}
