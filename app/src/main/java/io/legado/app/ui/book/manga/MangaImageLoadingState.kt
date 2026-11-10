package io.legado.app.ui.book.manga

/** A pending request is not a failure; a displayed image must never be covered on rebind. */
internal fun showMangaOriginalPlaceholder(
    loading: Boolean,
    failed: Boolean,
    hasDisplayedImage: Boolean,
    loadingIndicatorVisible: Boolean,
    hasPreview: Boolean,
): Boolean = !hasDisplayedImage && (failed || (loading && loadingIndicatorVisible && !hasPreview))
