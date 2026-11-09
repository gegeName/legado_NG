package io.legado.features.adapter

import android.os.ParcelFileDescriptor
import io.legado.features.api.host.FileLease
import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.*
import java.io.File

/** Compile-time absence is explicit; the host retains its original PDF implementation. */
@Suppress("UNUSED_PARAMETER")
class MangaPdfFeatures(root: File, open: (PdfDocumentKey) -> ParcelFileDescriptor) : AutoCloseable {
    companion object { const val available = false }
    fun generation(bookId: String) = 0L
    suspend fun resolve(request: PdfPageRequest, priority: ResourcePriority, force: Boolean = false, cacheOnly: Boolean = false, expectedEpoch: Long = 0L): FileLease = error("此构建未包含 PDF 分档渲染模块")
    fun window(document: PdfDocumentKey, page: Int, total: Int, ahead: Int, screen: Int, expectedEpoch: Long = 0L) = Unit
    fun pageCount(document: PdfDocumentKey, expectedEpoch: Long = 0L): Int = error("此构建未包含 PDF 分档渲染模块")
    fun cancelBook(bookId: String) = Unit
    fun trimMemory(critical: Boolean) = Unit
    override fun close() = Unit
}
