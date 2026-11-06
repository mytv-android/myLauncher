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
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 同一张图在不同尺寸下各存一份；组合时先同步取这里，避免滚动回来时闪一下实色卡
private val artworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private fun cacheKey(uri: String, width: Int, height: Int) = "$uri|$width|$height"

fun clearArtworkCache() {
    artworkCache.evictAll()
    LauncherImages.trimMemory()
}

@Composable
fun rememberArtwork(uri: String?, width: Int = 640, height: Int = 360): ImageBitmap? {
    val context = LocalContext.current
    val cached = uri?.let { artworkCache.get(cacheKey(it, width, height)) }?.asImageBitmap()
    val image by produceState(initialValue = cached, uri, width, height) {
        if (uri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        val key = cacheKey(uri, width, height)
        artworkCache.get(key)?.let {
            value = it.asImageBitmap()
            return@produceState
        }
        val bitmap = loadArtworkBitmap(context, uri, width, height)
        if (bitmap != null) {
            artworkCache.put(key, bitmap)
            value = bitmap.asImageBitmap()
        } else {
            value = null
        }
    }
    return image
}

/** 网络、content、android.resource、file 都走 Coil；本地 URI 失败时再用 ContentResolver 兜底。 */
suspend fun loadArtworkBitmap(context: Context, uri: String, width: Int, height: Int): Bitmap? {
    val viaCoil = try {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(width, height)
            .build()
        (LauncherImages.loader(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    } catch (_: Exception) {
        null
    }
    if (viaCoil != null) return viaCoil
    val scheme = Uri.parse(uri).scheme?.lowercase()
    if (scheme == "http" || scheme == "https") return null
    return withContext(Dispatchers.IO) { decodeLocal(context, uri, width, height) }
}

private fun decodeLocal(context: Context, uri: String, width: Int, height: Int): Bitmap? {
    return try {
        val parsed = Uri.parse(uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (options.outWidth / (sample * 2) >= width && options.outHeight / (sample * 2) >= height) {
            sample *= 2
        }
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, decode)
        }
    } catch (_: Exception) {
        null
    }
}
