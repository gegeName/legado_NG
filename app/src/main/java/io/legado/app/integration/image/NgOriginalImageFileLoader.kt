package io.legado.app.integration.image

import com.bumptech.glide.Registry
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.help.book.MangaImageCacheKey
import io.legado.app.help.glide.OkHttpModelLoader
import java.io.File
import java.io.InputStream

/** File prefetch returns the canonical book cache, without a second Glide disk copy. */
object NgOriginalImageFileLoader {
    fun register(registry: Registry) {
        registry.prepend(NgOriginalImageResources.Request::class.java, File::class.java, Factory())
        registry.prepend(NgOriginalImageResources.Request::class.java, InputStream::class.java, StreamFactory())
    }
    private class StreamFactory : ModelLoaderFactory<NgOriginalImageResources.Request, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<NgOriginalImageResources.Request, InputStream> {
            override fun handles(model: NgOriginalImageResources.Request) = true
            override fun buildLoadData(model: NgOriginalImageResources.Request, width: Int, height: Int, options: Options) =
                ModelLoader.LoadData(ObjectKey(MangaImageCacheKey.value(model.book, model.url)),
                    NgOriginalImageFetcher(model, options.get(OkHttpModelLoader.loadOnlyWifiOption) == true, options.get(NgOriginalImageResources.cacheOnlyOption) == true))
        }
        override fun teardown() {}
    }
    private class Factory : ModelLoaderFactory<NgOriginalImageResources.Request, File> {
        override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<NgOriginalImageResources.Request, File> {
            override fun handles(model: NgOriginalImageResources.Request) = true
            override fun buildLoadData(model: NgOriginalImageResources.Request, width: Int, height: Int, options: Options) =
                ModelLoader.LoadData(ObjectKey(MangaImageCacheKey.value(model.book, model.url)),
                    NgOriginalImageFileFetcher(model, options.get(OkHttpModelLoader.loadOnlyWifiOption) == true, options.get(NgOriginalImageResources.cacheOnlyOption) == true))
        }
        override fun teardown() {}
    }
}
