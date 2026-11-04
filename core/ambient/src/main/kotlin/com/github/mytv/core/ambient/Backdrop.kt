package com.github.mytv.core.ambient

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.Crossfade
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

@Composable
fun AmbientBackdrop(
    source: BackdropSource,
    modifier: Modifier = Modifier,
    crossfadeMillis: Int = 600,
) {
    val colors = LocalLauncherColors.current
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
                            colors = listOf(
                                dynamic.primary.copy(alpha = 0.55f),
                                Color.Transparent,
                            ),
                            startX = 0f,
                            endX = size.width * 0.62f,
                        ),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                dynamic.surface.copy(alpha = 0.88f),
                            ),
                            startY = size.height * 0.52f,
                            endY = size.height,
                        ),
                    )
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
