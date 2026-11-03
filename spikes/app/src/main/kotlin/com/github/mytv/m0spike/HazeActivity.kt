package com.github.mytv.m0spike

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.round

class HazeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "t1"
        val duration = intent.getLongExtra("duration", 8000L)
        if (mode == "t1" && Build.VERSION.SDK_INT < 31) {
            writeResult(mode, JSONObject().put("skipped", "api<31"))
            finish()
            return
        }
        setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                HazeScene(mode, duration) { result -> writeResult(mode, result) }
            }
        }
    }

    private fun writeResult(mode: String, json: JSONObject) {
        json.put("mode", mode)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MODEL)
            .put("fingerprint", Build.FINGERPRINT)
        Log.i(TAG, "haze $json")
        File(filesDir, "haze-$mode.json").writeText(json.toString())
    }
}

@Composable
private fun HazeScene(mode: String, duration: Long, onResult: (JSONObject) -> Unit) {
    val hazeState = remember { HazeState() }
    var offset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                offset = ((now - start) / 2_000_000f) % 4096f
            }
        }
    }

    LaunchedEffect(mode) {
        delay(1500)
        val frames = ArrayList<Long>()
        val choreographer = Choreographer.getInstance()
        val endNs = System.nanoTime() + duration * 1_000_000L
        val done = AtomicBoolean(false)
        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames.add(frameTimeNanos)
                if (System.nanoTime() < endNs) {
                    choreographer.postFrameCallback(this)
                } else if (done.compareAndSet(false, true)) {
                    onResult(frameStats(frames))
                }
            }
        })
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val background = if (mode == "t1") {
            Modifier.hazeSource(hazeState)
        } else {
            Modifier
        }
        Checkerboard(offset, Modifier.fillMaxSize().then(background))
        val bar = if (mode == "t1") {
            Modifier.hazeBlur(HazeInput.Backdrop(hazeState), HazeBlurDefaults.style)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .then(bar)
                .background(Color(0x33101018)),
        )
    }
}

@Composable
private fun Checkerboard(offset: Float, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color(0xFF0A0A10))
        val cell = 10f
        val period = cell * 2f
        val shift = offset % period
        var y = -period + shift
        while (y < size.height + period) {
            var x = -period + shift
            while (x < size.width + period) {
                if (((x / period).toInt() + (y / period).toInt()) % 2 == 0) {
                    drawRect(
                        color = Color(0xFFE8E8F0),
                        topLeft = Offset(x, y),
                        size = Size(cell, cell),
                    )
                }
                x += period
            }
            y += period
        }
    }
}

private fun frameStats(frames: List<Long>): JSONObject {
    val intervals = frames.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.sorted()
    fun pct(q: Double): Double = if (intervals.isEmpty()) 0.0 else intervals[min(intervals.size - 1, (intervals.size * q).toInt())]
    return JSONObject()
        .put("frames", intervals.size)
        .put("p50", round(pct(0.50) * 100) / 100.0)
        .put("p90", round(pct(0.90) * 100) / 100.0)
        .put("p99", round(pct(0.99) * 100) / 100.0)
        .put("avg", round(intervals.average() * 100) / 100.0)
}
