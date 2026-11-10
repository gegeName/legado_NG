package io.legado.app.help.glide

import android.content.Context
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.ExternalPreferredCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.integration.image.NgExtensionImageLoader
import io.legado.app.integration.image.NgOriginalImageFileLoader
import io.legado.app.integration.image.NgPdfImageLoader
import java.io.File
import java.io.InputStream


@Suppress("unused")
@GlideModule
class LegadoGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpModeLoaderFactory
        )
        registry.prepend(
            String::class.java,
            InputStream::class.java,
            LegadoDataUrlLoader.Factory()
        )
        registry.prepend(
            String::class.java,
            File::class.java,
            FilePathLoader.Factory()
        )
        NgExtensionImageLoader.register(registry)
        NgOriginalImageFileLoader.register(registry)
        NgPdfImageLoader.register(registry)
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        super.applyOptions(context, builder)
        val calculator = MemorySizeCalculator.Builder(context).build()
        val bitmapPool = AsyncRecycleBitmapPool(calculator.bitmapPoolSize)
        builder.setMemorySizeCalculator(calculator)
        builder.setBitmapPool(bitmapPool)
        builder.setDiskCache(ExternalPreferredCacheDiskCacheFactory(context, 1024 * 1024 * 1000))
        if (!BuildConfig.DEBUG && !AppConfig.recordLog) {
            builder.setLogLevel(Log.ERROR)
        }
    }
}
