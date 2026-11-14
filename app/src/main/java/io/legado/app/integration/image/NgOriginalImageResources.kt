package io.legado.app.integration.image

import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.request.RequestOptions
import com.script.rhino.runScriptWithContext
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getCachedBookImage
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.help.glide.progress.ProgressResponseBody
import io.legado.app.help.http.CookieStore
import io.legado.app.integration.source.NgExtensionSourceBridge
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageUtils
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.adapter.SharedImageResources
import io.legado.features.api.host.*
import io.legado.features.api.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import splitties.init.appCtx

/** Captures NG entities once; all task/subscriber/priority decisions belong to SharedImageResources. */
object NgOriginalImageResources {
    data class ImageReady(val bookId: String, val url: String)
    private val readyImages = MutableSharedFlow<ImageReady>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val imagesReady = readyImages.asSharedFlow()
    data class TranslationInput(val reference: io.legado.features.api.manga.TranslationRequest, val original: suspend () -> FileLease)
    data class Request(
        val book: Book,
        val sourceOrigin: String?,
        val url: String,
        val generation: Long,
        val configurationFingerprint: String,
    )
    val requestOption: Option<Request> = Option.memory("legadoFeatures.originalImage")
    val cacheOnlyOption: Option<Boolean> = Option.memory("legadoFeatures.originalCacheOnly", false)
    private data class Recipe(val book: Book, val source: BookSource?, val url: String, val target: File, var users: Int = 0)
    private val recipes = mutableMapOf<ImageResourceKey, Recipe>()
    private fun recipe(key: ImageResourceKey) = synchronized(recipes) { checkNotNull(recipes[key]) { "图片请求快照已释放" } }
    private fun fingerprint(source: BookSource?, url: String): String {
        val extension = source?.let(NgExtensionSourceBridge::key)?.let(ExtensionFeatures::imageConfiguration)
        val cookie = url.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.substringBefore(", {")?.substringBefore(",{")?.let(CookieStore::getCookie)
        // Preserve nulls and field boundaries: a missing rule differs from the JS expression "null".
        val config = GSON.toJson(listOf(source?.bookSourceUrl, source?.header, source?.getContentRule()?.imageDecode, source?.jsLib, source?.lastUpdateTime, cookie, extension))
        return MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun stamp(file: File): File {
        val name = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(appCtx.filesDir, "feature_image_versions/$name").also { it.parentFile?.mkdirs() }
    }
    private fun writeStamp(file: File, value: String) {
        val tag = stamp(file)
        val temp = File.createTempFile(".image-version-", ".tmp", tag.parentFile)
        try {
            temp.outputStream().use { it.write(value.toByteArray()); it.fd.sync() }
            if (!temp.renameTo(tag)) { check(!tag.exists() || tag.delete()); check(temp.renameTo(tag)) }
        } finally { temp.delete() }
    }
    private val cache = object : ImageCachePort {
        override fun target(key: ImageResourceKey) = recipe(key).target
        override fun cached(key: ImageResourceKey): File? {
            val file = getCachedBookImage(target(key)) ?: return null
            val tag = stamp(file)
            if (File(tag.path + ".pending").exists()) return null
            // Match the legacy reader: a valid BookHelp file is a cache hit regardless of source changes.
            if (!tag.exists()) writeStamp(file, key.configurationFingerprint)
            return file
        }
        override suspend fun download(key: ImageResourceKey): File {
            val request = recipe(key)
            val parent = checkNotNull(request.target.parentFile).apply { mkdirs() }
            val temp = File.createTempFile(".original-", ".tmp", parent)
            try {
                val plugin = PluginUris.image(request.url)
                if (plugin != null) {
                    ExtensionFeatures.openImage(plugin).use { lease ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val size = lease.stream.read(buffer)
                                if (size < 0) break
                                total += size
                                check(total <= 64L * 1024 * 1024) { "扩展图片过大" }
                                output.write(buffer, 0, size)
                                ProgressManager.report(request.url, total, lease.length)
                            }
                            ProgressManager.report(request.url, total, total)
                            output.fd.sync()
                        }
                    }
                } else {
                    val analyze = AnalyzeUrl(request.url, source = request.source, coroutineContext = currentCoroutineContext())
                    val stream = if (analyze.urlNoQuery.startsWith("data:")) analyze.getInputStreamAwait() else {
                        val response = analyze.getResponseAwait()
                        if (!response.isSuccessful) {
                            response.close()
                            throw IOException("图片请求失败：HTTP ${response.code}")
                        }
                        ProgressResponseBody(request.url, ProgressManager.LISTENER, response.body).byteStream()
                    }
                    val bytes = stream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            if (output.size().toLong() + size > 64L * 1024 * 1024) throw IOException("原图过大")
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    }
                    val decoded = runScriptWithContext(currentCoroutineContext()) { ImageUtils.decode(request.url, bytes, false, request.source, request.book) }
                    currentCoroutineContext().ensureActive()
                    if (decoded == null) throw IOException("漫画图片解密失败")
                    temp.outputStream().use { it.write(decoded); it.fd.sync() }
                }
                currentCoroutineContext().ensureActive()
                if (getCachedBookImage(temp) == null) throw IOException("图片下载后校验失败")
                return temp
            } catch (e: Throwable) { temp.delete(); throw e }
        }
        override fun publish(key: ImageResourceKey, temporary: File): File {
            val target = target(key)
            val pending = File(stamp(target).path + ".pending")
            pending.outputStream().use { it.write(key.configurationFingerprint.toByteArray()); it.fd.sync() }
            // Publish under the coordinator's lease/invalidation guard. No partial file copy fallback.
            if (!temporary.renameTo(target)) {
                check(!target.exists() || target.delete()) { "无法替换图片缓存" }
                check(temporary.renameTo(target)) { "无法保存图片缓存" }
            }
            writeStamp(target, key.configurationFingerprint)
            check(pending.delete()) { "无法完成图片缓存发布" }
            readyImages.tryEmit(ImageReady(key.bookId, key.resourceId))
            return target
        }
    }
    private val shared = SharedImageResources(cache)
    fun request(book: Book, sourceOrigin: String?, url: String): Request {
        val origin = sourceOrigin ?: book.origin
        val source = appDb.bookSourceDao.getBookSource(origin)
        return Request(book.copy(), origin, url, shared.generation(book.bookUrl), fingerprint(source, url))
    }
    suspend fun translationInput(book: Book, url: String, chapterId: String, pageIndex: Int, priority: ResourcePriority, generation: Long = shared.generation(book.bookUrl)): TranslationInput {
        return withContext(Dispatchers.IO) {
            if (shared.generation(book.bookUrl) != generation) throw CancellationException("图片会话已变化")
            val snapshot = book.copy()
            val source = appDb.bookSourceDao.getBookSource(snapshot.origin)?.let { GSON.fromJson(GSON.toJson(it), BookSource::class.java) }
            val sourceKey = source?.let(NgExtensionSourceBridge::key) ?: SourceKey("ng", ContentKind.MANGA, snapshot.origin)
            val key = ImageResourceKey(sourceKey, snapshot.bookUrl, url, fingerprint(source, url))
            TranslationInput(io.legado.features.api.manga.TranslationRequest(key, chapterId, pageIndex)) {
                resolve(snapshot, source, url, priority, generation = generation)
            }
        }
    }
    fun options(options: RequestOptions, book: Book, sourceOrigin: String?, url: String): RequestOptions =
        options.set(requestOption, request(book, sourceOrigin, url))
    fun hasCachedImage(book: Book, url: String): Boolean =
        getCachedBookImage(BookHelp.getImage(book, url)) != null
    fun fetcher(options: Options): NgOriginalImageFetcher? = options.get(requestOption)?.let {
        NgOriginalImageFetcher(it, options.get(OkHttpModelLoader.loadOnlyWifiOption) == true, options.get(cacheOnlyOption) == true)
    }
    suspend fun resolve(book: Book, source: BookSource?, url: String, priority: ResourcePriority, allowNetwork: Boolean = true, generation: Long = shared.generation(book.bookUrl)): FileLease {
      var acquired: FileLease? = null
      try { return withContext(Dispatchers.IO) {
        val snapshot = source?.let { GSON.fromJson(GSON.toJson(it), BookSource::class.java) }
        val sourceKey = snapshot?.let(NgExtensionSourceBridge::key) ?: SourceKey("ng", ContentKind.MANGA, book.origin)
        val key = ImageResourceKey(sourceKey, book.bookUrl, url, fingerprint(snapshot, url))
        synchronized(recipes) {
            recipes.getOrPut(key) { Recipe(book.copy(), snapshot, url, BookHelp.getImage(book, url)) }.users++
        }
        fun release() = synchronized(recipes) { recipes[key]?.let { if (--it.users == 0) recipes.remove(key, it) }; Unit }
        try {
            check(allowNetwork || cache.cached(key) != null) { "只在 Wi-Fi 加载图片" }
            val lease = shared.resolve(key, priority) { shared.generation(book.bookUrl) == generation }
            object : FileLease {
                private val released = AtomicBoolean(false)
                override val file get() = lease.file
                override val version get() = lease.version
                override fun close() { if (released.compareAndSet(false, true)) { lease.close(); release() } }
            }.also { acquired = it }
        } catch (e: Throwable) { release(); throw e }
      } } catch (e: Throwable) { acquired?.close(); throw e }
    }
    suspend fun resolve(request: Request, priority: ResourcePriority, allowNetwork: Boolean = true): FileLease {
        var acquired: FileLease? = null
        try { return withContext(Dispatchers.IO) {
            resolve(request.book, request.sourceOrigin?.let { appDb.bookSourceDao.getBookSource(it) }, request.url, priority, allowNetwork, request.generation).also { acquired = it }
        } } catch (e: Throwable) { acquired?.close(); throw e }
    }
    fun invalidateBook(bookId: String) = shared.invalidateBook(bookId)
    fun invalidateAll() = shared.invalidateAll()
    fun generation(bookId: String): Long = shared.generation(bookId)
    suspend fun pauseBook(bookId: String) = shared.pauseBook(bookId)
    fun resumeBook(bookId: String) = shared.resumeBook(bookId)
    fun deleteIfUnused(file: File, delete: () -> Unit) = shared.deleteIfUnused(file, delete)
    fun borrow(file: File): FileLease? = shared.borrow(file,
        version = { stamp(file).takeIf { it.isFile }?.let { "${it.readText()}:${file.lastModified()}:${file.length()}" }.orEmpty() },
        usable = { !File(stamp(file).path + ".pending").exists() && getCachedBookImage(file) != null })
    fun pin(book: Book, url: String) { File(stamp(BookHelp.getImage(book, url)).path + ".pinned").writeText("1") }
    fun discardAutomatic(file: File) {
        if (!File(stamp(file).path + ".pinned").exists()) deleteWhenUnused(file)
    }
    fun deleteWhenUnused(file: File) = shared.deleteWhenUnused(file) {
        check(!file.exists() || file.delete()) { "无法清理图片缓存" }
        stamp(file).let { it.delete(); File(it.path + ".pending").delete(); File(it.path + ".pinned").delete() }
    }
    /** Walk files individually so a live original-image lease survives cache clearing. */
    fun clearDirectory(directory: File, bookId: String? = null) {
        if (bookId != null) invalidateBook(bookId) else shared.invalidateAll()
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            if (file.isDirectory) file.delete()
            else if (!file.name.startsWith(".original-")) deleteWhenUnused(file)
        }
    }
    fun close() = shared.close()
}
