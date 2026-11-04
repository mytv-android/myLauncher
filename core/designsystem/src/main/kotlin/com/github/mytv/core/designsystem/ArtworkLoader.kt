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
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        if (source !== scaled) source.recycle()
        return pixels
    }
}
