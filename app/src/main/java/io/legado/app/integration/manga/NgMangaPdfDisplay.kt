package io.legado.app.integration.manga
import io.legado.app.help.book.isPdf

import androidx.compose.runtime.*
import io.legado.app.data.entities.Book
import io.legado.features.api.host.FileLease
import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.PdfTier
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun rememberNgPdfPreview(book: Book?, href: String, active: Boolean): File? {
    var preview by remember(book?.bookUrl, href) { mutableStateOf<File?>(null) }
    LaunchedEffect(book?.bookUrl, href, active) {
        if (book?.isPdf != true || !active || !NgMangaPdfBridge.available) return@LaunchedEffect
        var lease: FileLease? = null
        try {
            lease = NgMangaPdfBridge.resolve(book.copy(), href, ResourcePriority.VISIBLE, PdfTier.PREVIEW)
            preview = lease.file
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Main high-tier request exposes rendering failure. */ }
        finally { preview = null; lease?.close() }
    }
    return preview
}
