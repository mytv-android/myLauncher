package com.github.mytv.core.ambient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.asImageBitmap
import com.github.mytv.core.designsystem.ArtworkLoader
import com.github.mytv.core.designsystem.FrozenBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FrozenBlurGenerator(context: Context) {
    private val loader = ArtworkLoader(context)

    suspend fun generate(
        source: BackdropSource,
        screenWidth: Int,
        screenHeight: Int,
    ): FrozenBackdrop? = withContext(Dispatchers.Default) {
        val width = (screenWidth / 8).coerceAtLeast(1)
        val height = (screenHeight / 8).coerceAtLeast(1)
        val small = when (source) {
            BackdropSource.None -> null
            is BackdropSource.Solid -> solidBitmap(source.argb, width, height)
            is BackdropSource.Checker -> checkerBitmap(
                cell = (source.cellPx / 8).coerceAtLeast(1),
                first = source.first,
                second = source.second,
                width = width,
                height = height,
            )
            is BackdropSource.Artwork -> {
                val bitmap = loader.bitmap(source.uri, width * 4, height * 4) ?: return@withContext null
                fitCrop(bitmap, width, height)
            }
        } ?: return@withContext null
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = boxBlur(pixels, width, height, radius = 4)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(blurred, 0, width, 0, 0, width, height)
        FrozenBackdrop(
            image = result.asImageBitmap(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            bytes = result.byteCount.toLong(),
        )
    }

    private fun solidBitmap(argb: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private fun checkerBitmap(cell: Int, first: Int, second: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(first)
        val paint = Paint().apply { color = second }
        var y = 0
        var row = 0
        while (y < height) {
            var x = if (row % 2 == 0) 0 else cell
            while (x < width) {
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
                x += cell * 2
            }
            y += cell
            row++
        }
        return bitmap
    }

    private fun fitCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledW = (source.width * scale).toInt().coerceAtLeast(width)
        val scaledH = (source.height * scale).toInt().coerceAtLeast(height)
        val left = ((scaledW - width) / 2)
        val top = ((scaledH - height) / 2)
        val dst = Rect(-left, -top, -left + scaledW, -top + scaledH)
        canvas.drawBitmap(source, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return source
        val horizontal = IntArray(source.size)
        val output = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val c = source[y * width + sx]
                    a += (c ushr 24) and 0xFF
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                horizontal[y * width + x] = argb(a / count, r / count, g / count, b / count)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val c = horizontal[sy * width + x]
                    a += (c ushr 24) and 0xFF
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                output[y * width + x] = argb(a / count, r / count, g / count, b / count)
            }
        }
        return output
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
