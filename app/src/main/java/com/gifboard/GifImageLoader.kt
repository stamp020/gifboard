package com.gifboard

import android.content.Context
import com.facebook.cache.disk.DiskCacheConfig
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.backends.okhttp3.OkHttpImagePipelineConfigFactory
import com.facebook.imagepipeline.core.DownsampleMode

/**
 * Initializes Fresco for GIF loading.
 */
object GifImageLoader {

    private const val DISK_CACHE_MAX_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return

        synchronized(this) {
            if (initialized) return

            val diskCacheConfig = DiskCacheConfig.newBuilder(context.applicationContext)
                .setMaxCacheSize(DISK_CACHE_MAX_SIZE_BYTES)
                .build()

            val config = OkHttpImagePipelineConfigFactory
                .newBuilder(context.applicationContext, NetworkClients.shared)
                .setDownsampleMode(DownsampleMode.ALWAYS)
                .setMainDiskCacheConfig(diskCacheConfig)
                .build()

            Fresco.initialize(context.applicationContext, config)
            initialized = true
        }
    }
}
