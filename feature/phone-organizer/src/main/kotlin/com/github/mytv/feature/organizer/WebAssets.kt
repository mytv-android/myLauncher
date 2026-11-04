package com.github.mytv.feature.organizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Loads the bundled web client; nothing is fetched from the network. */
internal class WebAssets(private val context: Context) {
    @Volatile
    private var cachedIndex: ByteArray? = null

    fun index(themeCss: String): ByteArray {
        val cached = cachedIndex
        if (cached != null && themeCss.isBlank()) return cached
        val text = read("web/index.html")?.toString(Charsets.UTF_8)
            ?: return "<!doctype html><title>myLauncher</title>".toByteArray()
        val injected = text.replace("/*__THEME__*/", themeCss)
        val bytes = injected.toByteArray(Charsets.UTF_8)
        if (themeCss.isBlank()) cachedIndex = bytes
        return bytes
    }

    fun read(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> HttpSupport.HTML_MIME
        path.endsWith(".js") -> HttpSupport.JS_MIME
        path.endsWith(".css") -> HttpSupport.CSS_MIME
        path.endsWith(".svg") -> HttpSupport.SVG_MIME
        path.endsWith(".json") -> HttpSupport.JSON_MIME
        else -> "application/octet-stream"
    }
}

/** Builds the QR bitmap for the one-time token URL (ZXing core). */
object QrCodes {
    fun bitmap(content: String, size: Int = 768): Bitmap? = try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    } catch (_: Exception) {
        null
    }
}
