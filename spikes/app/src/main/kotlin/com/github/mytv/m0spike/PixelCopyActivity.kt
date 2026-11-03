package com.github.mytv.m0spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.File
import kotlin.math.round

class PixelCopyActivity : ComponentActivity() {
    private var surfaceView: SurfaceView? = null
    private var drawer: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 24) {
            writeResult(JSONObject().put("skipped", "api<24"))
            finish()
            return
        }
        setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                PixelScene { view ->
                    surfaceView = view
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            startDrawer(holder)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            drawer?.interrupt()
                            drawer = null
                        }
                    })
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ capture(0) }, 2500)
    }

    override fun onDestroy() {
        drawer?.interrupt()
        drawer = null
        super.onDestroy()
    }

    private fun startDrawer(holder: SurfaceHolder) {
        if (drawer?.isAlive == true) return
        drawer = Thread {
            var tick = 0
            while (!Thread.currentThread().isInterrupted) {
                val canvas = try {
                    holder.lockCanvas()
                } catch (e: Exception) {
                    null
                }
                if (canvas != null) {
                    try {
                        drawBars(canvas, tick)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
                tick += 4
                try {
                    Thread.sleep(16)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.start() }
    }

    private fun drawBars(canvas: Canvas, tick: Int) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val colors = intArrayOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.YELLOW, Color.CYAN, Color.MAGENTA,
        )
        val step = w / colors.size
        val paint = Paint()
        for (i in colors.indices) {
            paint.color = colors[i]
            canvas.drawRect(i * step, 0f, (i + 1) * step, h, paint)
        }
        paint.color = Color.BLACK
        val x = (tick % w.toInt()).toFloat()
        canvas.drawRect(x, 0f, (x + 80f).coerceAtMost(w), h, paint)
    }

    private fun capture(attempt: Int) {
        val view = surfaceView
        if (view == null || view.width == 0 || view.height == 0) {
            if (attempt < 15) {
                Handler(Looper.getMainLooper()).postDelayed({ capture(attempt + 1) }, 300)
            } else {
                writeResult(JSONObject().put("surfaceError", "view_not_ready"))
                finish()
            }
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val start = SystemClock.elapsedRealtime()
        PixelCopy.request(view, bitmap, { result ->
            val elapsed = SystemClock.elapsedRealtime() - start
            val surface = JSONObject()
                .put("result", result)
                .put("ok", result == PixelCopy.SUCCESS)
                .put("elapsedMs", elapsed)
                .put("meanLuma", meanLuma(bitmap))
                .put("blackRatio", blackRatio(bitmap))
            Log.i(TAG, "pixelcopy surface=$surface")
            if (Build.VERSION.SDK_INT >= 26) {
                captureWindow(surface)
            } else {
                writeResult(JSONObject().put("surface", surface))
                finish()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun captureWindow(surface: JSONObject) {
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val start = SystemClock.elapsedRealtime()
        PixelCopy.request(window, bitmap, { result ->
            val elapsed = SystemClock.elapsedRealtime() - start
            val windowData = JSONObject()
                .put("result", result)
                .put("ok", result == PixelCopy.SUCCESS)
                .put("elapsedMs", elapsed)
                .put("meanLuma", meanLuma(bitmap))
                .put("blackRatio", blackRatio(bitmap))
            val data = JSONObject()
                .put("surface", surface)
                .put("window", windowData)
                .put("drm", "not_tested")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("device", Build.MODEL)
            writeResult(data)
            finish()
        }, Handler(Looper.getMainLooper()))
    }

    private fun meanLuma(bitmap: Bitmap): Double {
        var sum = 0L
        var count = 0
        for (y in 0 until bitmap.height step 16) {
            for (x in 0 until bitmap.width step 16) {
                val c = bitmap.getPixel(x, y)
                val luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                sum += luma
                count++
            }
        }
        return if (count == 0) 0.0 else round(sum.toDouble() / count * 100) / 100.0
    }

    private fun blackRatio(bitmap: Bitmap): Double {
        var black = 0
        var count = 0
        for (y in 0 until bitmap.height step 16) {
            for (x in 0 until bitmap.width step 16) {
                val c = bitmap.getPixel(x, y)
                val luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (luma < 4) black++
                count++
            }
        }
        return if (count == 0) 0.0 else round(black.toDouble() / count * 10000) / 10000.0
    }

    private fun writeResult(json: JSONObject) {
        Log.i(TAG, "pixelcopy $json")
        File(filesDir, "pixelcopy.json").writeText(json.toString())
    }
}

@Composable
private fun PixelScene(factory: (SurfaceView) -> Unit) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).also(factory)
        },
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Black),
    )
}
