package io.legado.app.integration.image

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import io.legado.app.utils.isWifiConnect
import io.legado.features.api.host.FileLease
import io.legado.features.api.host.ResourcePriority
import kotlinx.coroutines.*
import splitties.init.appCtx
import java.io.InputStream
import java.io.File

/** Glide owns one subscription until cleanup; streams and file results retain their lease. */
abstract class NgOriginalResourceFetcher<T : Any>(
    private val request: NgOriginalImageResources.Request,
    private val wifiOnly: Boolean,
    private val cacheOnly: Boolean,
    private val nearPrefetch: Boolean,
) : DataFetcher<T> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private var cancelled = false
    private var lease: FileLease? = null
    private var data: T? = null
    protected abstract fun open(file: File): T
    protected open fun close(data: T) {}
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in T>) {
        scope.launch {
            var acquired: FileLease? = null
            var opened: T? = null
            try {
                val resourcePriority = when {
                    nearPrefetch -> ResourcePriority.NEAR_PREFETCH
                    priority == Priority.LOW -> ResourcePriority.PREFETCH
                    else -> ResourcePriority.VISIBLE
                }
                acquired = NgOriginalImageResources.resolve(request, resourcePriority,
                    allowNetwork = !cacheOnly && (!wifiOnly || appCtx.isWifiConnect))
                ensureActive()
                opened = open(acquired.file)
                synchronized(monitor) {
                    if (cancelled) throw CancellationException("图片加载已取消")
                    lease = acquired; data = opened
                    acquired = null; opened = null
                    callback.onDataReady(data)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { synchronized(monitor) { if (!cancelled) callback.onLoadFailed(e) } }
            finally { opened?.let { runCatching { close(it) } }; acquired?.close() }
        }
    }
    override fun cleanup() {
        scope.cancel()
        synchronized(monitor) {
            cancelled = true
            data?.let { runCatching { close(it) } }; data = null
            lease?.close(); lease = null
        }
    }
    override fun cancel() = cleanup()
    override fun getDataSource() = DataSource.LOCAL
}

class NgOriginalImageFetcher(request: NgOriginalImageResources.Request, wifiOnly: Boolean, cacheOnly: Boolean = false, nearPrefetch: Boolean = false) :
    NgOriginalResourceFetcher<InputStream>(request, wifiOnly, cacheOnly, nearPrefetch) {
    override fun open(file: File) = file.inputStream()
    override fun close(data: InputStream) = data.close()
    override fun getDataClass() = InputStream::class.java
}

class NgOriginalImageFileFetcher(request: NgOriginalImageResources.Request, wifiOnly: Boolean, cacheOnly: Boolean, nearPrefetch: Boolean = false) :
    NgOriginalResourceFetcher<File>(request, wifiOnly, cacheOnly, nearPrefetch) {
    override fun open(file: File) = file
    override fun getDataClass() = File::class.java
}
