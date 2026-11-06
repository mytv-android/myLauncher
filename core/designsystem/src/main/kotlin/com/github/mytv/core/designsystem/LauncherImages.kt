package com.github.mytv.core.designsystem

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * 桌面唯一的图片加载器：节目海报、台标、背景图都从这里取。
 *
 * - 网络层用 OkHttp；频道里的 http 海报一律改走 https，不放开明文流量。
 * - 取色和冻结模糊要读像素，所以关闭硬件位图。
 * - 磁盘缓存 64 MB，放在 cacheDir 下，系统清理缓存时可以整体删除。
 */
object LauncherImages {
    @Volatile
    private var instance: ImageLoader? = null

    fun loader(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** 清空内存缓存（onTrimMemory 用）；磁盘缓存保留。 */
    fun trimMemory() {
        instance?.memoryCache?.clear()
    }

    private fun build(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(HttpsUpgrade)
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.12)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .allowHardware(false)
            .build()
    }

    private object HttpsUpgrade : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!request.url.isHttps) {
                val url = request.url.newBuilder().scheme("https").build()
                return chain.proceed(request.newBuilder().url(url).build())
            }
            return chain.proceed(request)
        }
    }
}
