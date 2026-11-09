package io.legado.app.integration.image

import com.bumptech.glide.Registry
import com.bumptech.glide.Priority
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.*
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.data.entities.Book
import io.legado.app.integration.manga.NgMangaPdfBridge
import io.legado.features.api.host.*
import io.legado.features.api.manga.PdfTier
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream

object NgPdfImageLoader {
    data class Request(val book: Book, val href: String, val generation: Long = NgMangaPdfBridge.generation(book.bookUrl))
    fun register(registry: Registry) {
        registry.prepend(Request::class.java, InputStream::class.java, Factory<InputStream>(false))
        registry.prepend(Request::class.java, File::class.java, Factory<File>(true))
    }
    private class Factory<T : Any>(private val file: Boolean) : ModelLoaderFactory<Request, T> {
        override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<Request, T> {
            override fun handles(model: Request) = true
            override fun buildLoadData(model: Request, width: Int, height: Int, options: Options) =
                ModelLoader.LoadData(ObjectKey("pdf:${model.book.bookUrl}:${model.href}:${model.generation}"), Fetcher<T>(model, file, options.get(NgOriginalImageResources.cacheOnlyOption) == true))
        }
        override fun teardown() {}
    }
    private class Fetcher<T : Any>(private val request: Request, private val file: Boolean, private val cacheOnly: Boolean) : DataFetcher<T> {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private var cancelled = false
        private var lease: FileLease? = null
        private var stream: InputStream? = null
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in T>) {
            scope.launch {
                var acquired: FileLease? = null
                var opened: InputStream? = null
                try {
                    acquired = NgMangaPdfBridge.resolve(request.book, request.href,
                        if (priority == Priority.LOW) ResourcePriority.PREFETCH else ResourcePriority.USER_REQUEST,
                        if (priority == Priority.LOW) PdfTier.PREVIEW else PdfTier.HIGH, cacheOnly = cacheOnly, generation = request.generation)
                    ensureActive()
                    if (!file) opened = acquired.file.inputStream()
                    synchronized(lock) {
                        if (cancelled) throw CancellationException("PDF 加载取消")
                        lease = acquired; stream = opened
                        @Suppress("UNCHECKED_CAST") val result = (if (file) acquired.file else opened) as T
                        acquired = null; opened = null
                        callback.onDataReady(result)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { synchronized(lock) { if (!cancelled) callback.onLoadFailed(e) } }
                finally { opened?.close(); acquired?.close() }
            }
        }
        override fun cancel() = cleanup()
        override fun cleanup() {
            scope.cancel()
            synchronized(lock) { cancelled = true; try { stream?.close() } finally { stream = null; lease?.close(); lease = null } }
        }
        override fun getDataSource() = DataSource.LOCAL
        @Suppress("UNCHECKED_CAST") override fun getDataClass(): Class<T> = (if (file) File::class.java else InputStream::class.java) as Class<T>
    }
}
