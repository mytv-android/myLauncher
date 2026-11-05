package com.github.mytv.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

class ArtworkLoader(context: Context) {
    private val appContext = context.applicationContext
    private val loader by lazy { ImageLoader.Builder(appContext).build() }

    suspend fun bitmap(uri: String, width: Int, height: Int): Bitmap? = try {
        val request = ImageRequest.Builder(appContext)
            .data(uri)
            .size(width, height)
            .allowHardware(false)
            .build()
        (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
    } catch (_: Exception) {
        null
    }

    suspend fun pixels(uri: String, width: Int, height: Int): IntArray? {
        val source = bitmap(uri, width, height) ?: return null
        // source 可能来自 Coil 的内存缓存，不能回收；只回收这里新建的缩放副本
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        return try {
            IntArray(width * height).also {
                scaled.getPixels(it, 0, width, 0, 0, width, height)
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
