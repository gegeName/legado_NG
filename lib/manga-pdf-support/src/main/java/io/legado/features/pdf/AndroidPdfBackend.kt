package io.legado.features.pdf

import io.legado.features.api.manga.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

interface PdfRenderBackend : AutoCloseable {
    fun valid(file: File): Boolean
    fun render(request: PdfPageRequest, temporary: File)
    fun release(document: PdfDocumentKey)
    fun pageCount(document: PdfDocumentKey): Int = error("PDF 后端不提供页数")
}

/** Calls, including close, are serialized with in-flight native rendering. */
class AndroidPdfBackend(private val open: (PdfDocumentKey) -> ParcelFileDescriptor) : PdfRenderBackend {
    private var key: PdfDocumentKey? = null
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    override fun valid(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth in 1..4096 && options.outHeight in 1..4096
    }
    private fun document(document: PdfDocumentKey): PdfRenderer {
        if (key != document) {
            close()
            val next = open(document)
            try { renderer = PdfRenderer(next); descriptor = next; key = document }
            catch (e: Throwable) { next.close(); throw e }
        }
        return checkNotNull(renderer)
    }
    @Synchronized override fun pageCount(document: PdfDocumentKey): Int = document(document).pageCount
    @Synchronized override fun render(request: PdfPageRequest, temporary: File) {
        val pdf = document(request.document)
        require(request.page in 0 until pdf.pageCount) { "PDF 页码越界" }
        pdf.openPage(request.page).use { page ->
            var (width, height) = PdfPolicies.size(page.width, page.height, request.screenWidth, request.tier, Runtime.getRuntime().maxMemory())
            repeat(3) { attempt ->
                var bitmap: Bitmap? = null
                try {
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val format = if (request.tier == PdfTier.HIGH && mostlyGray(bitmap)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    FileOutputStream(temporary).use { stream ->
                        check(bitmap.compress(format, if (request.tier == PdfTier.PREVIEW) 75 else 85, stream)) { "PDF 缓存编码失败" }
                        stream.fd.sync()
                    }
                    return
                } catch (e: OutOfMemoryError) {
                    if (attempt == 2) throw e
                    width = maxOf(1, width / 2); height = maxOf(1, height / 2)
                } finally { bitmap?.recycle() }
            }
        }
    }
    private fun mostlyGray(bitmap: Bitmap): Boolean {
        var gray = 0; var samples = 0
        val colors = hashSetOf<Int>()
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 32)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 32)) {
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color); val green = Color.green(color); val blue = Color.blue(color)
                if (maxOf(red, green, blue) - minOf(red, green, blue) <= 12) gray++
                colors += color and 0xfff0f0f0.toInt()
                samples++
            }
        }
        return gray >= samples * 0.9 || colors.size <= 32
    }
    @Synchronized override fun release(document: PdfDocumentKey) { if (key == document) close() }
    @Synchronized override fun close() {
        try { renderer?.close() } finally {
            renderer = null
            descriptor?.close(); descriptor = null; key = null
        }
    }
}
