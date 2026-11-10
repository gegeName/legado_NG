package io.legado.app.integration.manga

import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.features.adapter.MangaReaderFeatures
import io.legado.features.api.manga.PageKey
import io.legado.features.api.manga.ReaderPage
import io.legado.features.api.manga.DisplayItem
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import io.legado.app.ui.book.manga.MangaReaderState
import io.legado.app.ui.book.manga.MangaImage

/** Original NG pages remain the only progress and chapter identities. */
internal data class NgMangaSpread(val originals: List<MangaPage>, val physical: List<MangaPage>) : BaseMangaPage {
    override val chapterIndex get() = originals.first().chapterIndex
    override val index get() = originals.first().index
}

internal object NgMangaLayout {
    fun neutral(page: MangaPage) = ReaderPage(PageKey(page.chapterIndex.toString(), page.index, page.mImageUrl), page.chapterIndex, page.imageCount)
    fun realPages(page: BaseMangaPage): List<MangaPage> = when (page) {
        is MangaPage -> listOf(page)
        is NgMangaSpread -> page.originals
        else -> emptyList()
    }
    fun display(pages: List<BaseMangaPage>, enabled: Boolean, rtl: Boolean): List<BaseMangaPage> {
        if (!enabled) return pages
        val originals = pages.filterIsInstance<MangaPage>().associateBy { neutral(it).key }
        var position = 0
        return MangaReaderFeatures.spreads(pages.map { (it as? MangaPage)?.let(::neutral) }, true, rtl).items.map { item ->
            if (item == null) pages[position++] else {
                position += item.pages.size
                val real = item.pages.map { originals.getValue(it.key) }
                if (real.size == 1) real.first() else NgMangaSpread(real, listOfNotNull(item.left, item.right).map { originals.getValue(it.key) })
            }
        }
    }
    fun slot(display: List<BaseMangaPage>, original: BaseMangaPage?, fallback: Int): Int =
        display.indexOfFirst { it == original || realPages(it).contains(original) }.takeIf { it >= 0 } ?: fallback.coerceIn(0, display.size)
    fun prefetchItems(display: List<BaseMangaPage>): List<DisplayItem?> = display.map { page ->
        realPages(page).map(::neutral).takeIf { it.isNotEmpty() }
            ?.let { DisplayItem(it, it.first(), it.getOrNull(1)) }
    }
    fun prefetch(items: List<DisplayItem?>, visible: Int, forward: Boolean, count: Int) =
        MangaReaderFeatures.prefetchItems(items, visible, forward, count)
    /** Keep the visible originals attached to the preloader until their foreground request takes over. */
    fun retainedPrefetchResources(display: List<BaseMangaPage>, visible: Int, networkResources: Collection<String>): Set<String> {
        return buildSet {
            addAll(networkResources)
            display.getOrNull(visible)?.let { addAll(realPages(it).map(MangaPage::mImageUrl)) }
        }
    }
    fun progress(page: BaseMangaPage, selected: MangaPage?): BaseMangaPage =
        if (page is NgMangaSpread) selected?.takeIf { it in page.originals } ?: page.originals.first() else page
}

@Composable internal fun NgMangaSpreadImage(page: NgMangaSpread, ui: MangaReaderState, height: Dp, modifier: Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier) {
            page.physical.forEach { original ->
                MangaImage(original, ui, height, Modifier.weight(1f).fillMaxHeight(), paged = true)
            }
        }
    }
}
