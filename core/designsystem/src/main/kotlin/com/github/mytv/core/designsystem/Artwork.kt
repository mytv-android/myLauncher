package com.github.mytv.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
fun rememberArtwork(uri: String?, width: Int = 640, height: Int = 360): ImageBitmap? {
    val context = LocalContext.current
    val cached = uri?.let { artworkCache.get(it) }?.asImageBitmap()
    val image by produceState<ImageBitmap?>(initialValue = cached, uri, width, height) {
        if (uri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        artworkCache.get(uri)?.let {
            value = it.asImageBitmap()
            return@produceState
        }
        val bitmap = withContext(Dispatchers.IO) { loadBitmap(context, uri, width, height) }
        if (bitmap != null) {
            artworkCache.put(uri, bitmap)
            value = bitmap.asImageBitmap()
        }
    }
    return image
}

private fun loadBitmap(context: Context, uri: String, width: Int, height: Int): Bitmap? {
    return try {
        val parsed = Uri.parse(uri)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (options.outWidth / (sample * 2) >= width && options.outHeight / (sample * 2) >= height) {
            sample *= 2
        }
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, decode)
        }
    } catch (_: Exception) {
        null
    }
}
