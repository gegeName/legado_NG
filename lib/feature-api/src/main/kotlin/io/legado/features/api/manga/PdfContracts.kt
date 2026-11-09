package io.legado.features.api.manga

enum class PdfTier { PREVIEW, HIGH }
data class PdfDocumentKey(val bookId: String, val version: String)
data class PdfPageRequest(val document: PdfDocumentKey, val page: Int, val screenWidth: Int, val tier: PdfTier) {
    init { require(page >= 0 && screenWidth > 0) }
}
