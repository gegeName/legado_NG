package io.legado.features.adapter

import android.os.ParcelFileDescriptor
import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.*
import io.legado.features.pdf.*
import java.io.File

class MangaPdfFeatures(root: File, open: (PdfDocumentKey) -> ParcelFileDescriptor) : AutoCloseable {
    companion object { const val available = true }
    private val resources = PdfResources(root, AndroidPdfBackend(open))
    fun generation(bookId: String) = resources.generation(bookId)
    suspend fun resolve(request: PdfPageRequest, priority: ResourcePriority, force: Boolean = false, cacheOnly: Boolean = false, expectedEpoch: Long = generation(request.document.bookId)) = resources.resolve(request, priority, force, cacheOnly, expectedEpoch)
    fun window(document: PdfDocumentKey, page: Int, total: Int, ahead: Int, screen: Int, expectedEpoch: Long = generation(document.bookId)) = resources.updateWindow(document, page, total, ahead, screen, expectedEpoch)
    fun pageCount(document: PdfDocumentKey, expectedEpoch: Long = generation(document.bookId)) = resources.pageCount(document, expectedEpoch)
    fun cancelBook(bookId: String) = resources.cancelBook(bookId)
    fun trimMemory(critical: Boolean) = resources.trimMemory(critical)
    override fun close() = resources.close()
}
