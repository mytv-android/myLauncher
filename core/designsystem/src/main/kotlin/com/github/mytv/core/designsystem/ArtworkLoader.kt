package com.github.mytv.core.designsystem

import android.content.Context
import android.graphics.Bitmap

class ArtworkLoader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun bitmap(uri: String, width: Int, height: Int): Bitmap? =
        loadArtworkBitmap(appContext, uri, width, height)

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
