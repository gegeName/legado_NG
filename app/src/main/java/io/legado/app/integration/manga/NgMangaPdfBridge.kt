package io.legado.app.integration.manga

import android.content.Context
import android.os.ParcelFileDescriptor
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.SystemUtils
import io.legado.app.utils.isContentScheme
import io.legado.features.adapter.MangaPdfFeatures
import io.legado.features.api.host.FileLease
import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.*
import kotlinx.coroutines.*
import splitties.init.appCtx
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.ConcurrentHashMap

object NgMangaPdfBridge {
    val available get() = MangaPdfFeatures.available
    private data class Snapshot(val uri: android.net.Uri)
    private val documents = ConcurrentHashMap<PdfDocumentKey, Snapshot>()
    private var instance: MangaPdfFeatures? = null
    private val epochs = ConcurrentHashMap<String, Long>()
    fun generation(bookId: String) = epochs[bookId] ?: 0L
    private fun requireCurrent(bookId: String, generation: Long) {
        if (generation(bookId) != generation) throw CancellationException("PDF 请求会话已失效")
    }
    @Synchronized private fun engine() = instance ?: MangaPdfFeatures(File(appCtx.filesDir, "feature_pdf_cache")) { key ->
        val uri = checkNotNull(documents[key]) { "PDF 文档会话已关闭" }.uri
        if (uri.isContentScheme()) checkNotNull(appCtx.contentResolver.openFileDescriptor(uri, "r"))
        else ParcelFileDescriptor.open(File(checkNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
    }.also { instance = it }
    private fun document(book: Book): PdfDocumentKey {
        val uri = book.getLocalUri()
        val version = if (uri.isContentScheme()) {
            runCatching {
                appCtx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) listOf(android.provider.OpenableColumns.SIZE, "last_modified", android.provider.OpenableColumns.DISPLAY_NAME).joinToString(":") { column ->
                        cursor.getColumnIndex(column).takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
                    } else "unknown"
                }
            }.getOrNull() ?: "unknown"
        } else File(checkNotNull(uri.path)).let { "${it.lastModified()}:${it.length()}" }
        return PdfDocumentKey(book.bookUrl, "$uri:$version").also { documents[it] = Snapshot(uri) }
    }
    fun fingerprint(book: Book) = document(book).version
    fun ahead(): Int = appCtx.getSharedPreferences("feature_modules", Context.MODE_PRIVATE).getInt("manga-pdf:ahead", 8).coerceIn(0, 16)
    fun saveAhead(value: Int) { appCtx.getSharedPreferences("feature_modules", Context.MODE_PRIVATE).edit().putInt("manga-pdf:ahead", value.coerceIn(0, 16)).apply() }
    suspend fun resolve(book: Book, href: String, priority: ResourcePriority, tier: PdfTier = PdfTier.HIGH, force: Boolean = false, cacheOnly: Boolean = false, generation: Long = generation(book.bookUrl)): FileLease {
        var acquired: FileLease? = null
        try { return withContext(Dispatchers.IO) {
            if (!available) {
                requireCurrent(book.bookUrl, generation)
                check(!cacheOnly || io.legado.app.help.book.BookHelp.isImageExist(book, href)) { "PDF 页面尚未缓存" }
                val file = io.legado.app.model.ImageProvider.cacheImage(book, href, null)
                acquired = checkNotNull(io.legado.app.integration.image.NgOriginalImageResources.borrow(file)) { "PDF 页面无法读取" }
                requireCurrent(book.bookUrl, generation)
                return@withContext checkNotNull(acquired)
            }
            val (key, features, epoch) = synchronized(this@NgMangaPdfBridge) {
                requireCurrent(book.bookUrl, generation)
                val key = document(book)
                val features = engine()
                Triple(key, features, features.generation(book.bookUrl))
            }
            val page = href.substringBefore('?').toInt()
            features.resolve(PdfPageRequest(key, page, SystemUtils.screenWidthPx.coerceAtLeast(1), tier), priority, force, cacheOnly, epoch).also {
                acquired = it; requireCurrent(book.bookUrl, generation)
            }
        } } catch (e: Throwable) { acquired?.close(); throw e }
    }
    fun open(book: Book, href: String): java.io.InputStream {
        val lease = runBlocking { resolve(book.copy(), href, ResourcePriority.USER_REQUEST) }
        return try { object : FilterInputStream(lease.file.inputStream()) {
            private val closed = java.util.concurrent.atomic.AtomicBoolean()
            override fun close() { if (closed.compareAndSet(false, true)) try { super.close() } finally { lease.close() } }
        } } catch (e: Throwable) { lease.close(); throw e }
    }
    suspend fun window(book: Book, href: String, generation: Long = generation(book.bookUrl)) = withContext(Dispatchers.IO) {
        if (!available) return@withContext
        val (key, features, epoch) = synchronized(this@NgMangaPdfBridge) {
            requireCurrent(book.bookUrl, generation)
            val key = document(book)
            val features = engine()
            Triple(key, features, features.generation(book.bookUrl))
        }
        val total = features.pageCount(key, epoch)
        synchronized(this@NgMangaPdfBridge) {
            requireCurrent(book.bookUrl, generation)
            features.window(key, href.substringBefore('?').toInt(), total, ahead(), SystemUtils.screenWidthPx.coerceAtLeast(1), epoch)
        }
    }
    @Synchronized fun cancelBook(bookId: String) {
        epochs.compute(bookId) { _, previous -> (previous ?: 0L) + 1 }
        instance?.cancelBook(bookId)
        documents.keys.removeAll { it.bookId == bookId }
    }
    @Synchronized fun trimMemory(critical: Boolean) { instance?.trimMemory(critical) }
    @Synchronized fun reset() {
        documents.keys.map { it.bookId }.distinct().forEach(::cancelBook)
        instance?.close(); instance = null; documents.clear()
    }
}
