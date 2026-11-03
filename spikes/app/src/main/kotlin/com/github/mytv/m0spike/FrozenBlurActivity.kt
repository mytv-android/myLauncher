package com.github.mytv.m0spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.round

class FrozenBlurActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                var text by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    val result = withContext(Dispatchers.Default) { buildFrozen() }
                    image = result.first.asImageBitmap()
                    text = result.second.toString()
                    Log.i(TAG, "frozen ${result.second}")
                    File(filesDir, "frozen.json").writeText(result.second.toString())
                }
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    image?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(androidx.compose.ui.graphics.Color(0x66101018)),
                    )
                    if (text.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = text,
                            modifier = Modifier.fillMaxSize(),
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }
        }
    }

    private fun buildFrozen(): Pair<Bitmap, JSONObject> {
        val width = 1280
        val height = 720
        val first = pattern(width, height, 0)
        val firstStart = System.nanoTime()
        val firstBlur = blur(first)
        val firstMs = (System.nanoTime() - firstStart) / 1_000_000.0
        val second = pattern(width, height, 1)
        val swapStart = System.nanoTime()
        val secondBlur = blur(second)
        val swapMs = (System.nanoTime() - swapStart) / 1_000_000.0
        val data = JSONObject()
            .put("generateMs", round(firstMs * 100) / 100.0)
            .put("swapMs", round(swapMs * 100) / 100.0)
            .put("width", width)
            .put("height", height)
            .put("alignmentOffsetPx", 0)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MODEL)
        return secondBlur to data
    }

    private fun pattern(width: Int, height: Int, variant: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint()
        val cell = 20
        for (y in 0 until height step cell) {
            for (x in 0 until width step cell) {
                val even = ((x / cell) + (y / cell) + variant) % 2 == 0
                paint.color = if (even) 0xFFE8E8F0.toInt() else 0xFF101018.toInt()
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
            }
        }
        return bitmap
    }

    private fun blur(source: Bitmap): Bitmap {
        val sw = source.width / 8
        val sh = source.height / 8
        val small = Bitmap.createScaledBitmap(source, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        boxBlur(pixels, sw, sh, 6)
        val blurredSmall = Bitmap.createBitmap(pixels, sw, sh, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val c = pixels[yy * width + xx]
                        a += (c ushr 24) and 0xFF
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        n++
                    }
                }
                out[y * width + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        out.copyInto(pixels)
    }
}
