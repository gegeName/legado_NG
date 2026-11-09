package io.legado.app.integration.image

import com.bumptech.glide.Priority
import com.bumptech.glide.Registry
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.*
import com.bumptech.glide.signature.ObjectKey
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.api.source.ImageStream
import io.legado.features.api.source.PluginUris
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.book.getCachedBookImage
import io.legado.app.utils.isWifiConnect
import splitties.init.appCtx
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import java.io.InputStream

object NgExtensionImageLoader {
    fun handles(uri: String?) = uri != null && PluginUris.image(uri) != null
    suspend fun downloadOrNull(uri: String): ByteArray? {
        val ref = PluginUris.image(uri) ?: return null
        return ExtensionFeatures.openImage(ref).use { lease ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = lease.stream.read(buffer)
                if (count < 0) break
                check(output.size().toLong() + count <= 64L * 1024 * 1024) { "扩展图片过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    fun register(registry: Registry) {
        registry.prepend(String::class.java, InputStream::class.java, Factory<String> { it })
        registry.prepend(GlideUrl::class.java, InputStream::class.java, Factory<GlideUrl> { it.toStringUrl() })
    }
    private class Factory<T : Any>(private val url: (T) -> String) : ModelLoaderFactory<T, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<T, InputStream> {
            override fun handles(model: T) = PluginUris.image(url(model)) != null
            override fun buildLoadData(model: T, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
                val uri = url(model)
                return if (handles(model)) ModelLoader.LoadData(ObjectKey(uri), NgOriginalImageResources.fetcher(options) ?: Fetcher(uri, options)) else null
            }
        }
        override fun teardown() {}
    }
    private class Fetcher(private val uri: String, private val options: Options) : DataFetcher<InputStream> {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var lease: ImageStream? = null
        @Volatile private var cached: InputStream? = null
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            scope.launch {
                try {
                    val file = options.get(OkHttpModelLoader.mangaImageCacheOption)?.let { getCachedBookImage(File(it)) }
                    if (file != null) {
                        cached = file.inputStream()
                        ensureActive()
                        callback.onDataReady(cached)
                        return@launch
                    }
                    check(options.get(OkHttpModelLoader.loadOnlyWifiOption) != true || appCtx.isWifiConnect) { "只在 Wi-Fi 加载图片" }
                    lease = ExtensionFeatures.openImage(checkNotNull(PluginUris.image(uri)))
                    ensureActive()
                    callback.onDataReady(checkNotNull(lease).stream)
                } catch (e: CancellationException) { lease?.close(); throw e }
                catch (e: Exception) { lease?.close(); callback.onLoadFailed(e) }
            }
        }
        override fun cleanup() { scope.cancel(); lease?.close(); lease = null; cached?.close(); cached = null }
        override fun cancel() = cleanup()
        override fun getDataClass() = InputStream::class.java
        override fun getDataSource() = DataSource.REMOTE
    }
}
