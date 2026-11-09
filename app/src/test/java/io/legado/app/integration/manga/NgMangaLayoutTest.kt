package io.legado.app.integration.manga

import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import org.junit.Assert.*
import org.junit.Test

class NgMangaLayoutTest {
    private fun page(chapter: Int, index: Int) = MangaPage(chapter, 5, "$chapter/$index", index, 3)
    @Test fun rtlPairsMapBothOriginalPagesAndPreserveSelectedProgress() {
        val first = page(0, 0)
        val second = page(0, 1)
        val third = page(0, 2)
        val display = NgMangaLayout.display(listOf(first, second, third, page(1, 0)), true, true)
        assertEquals(3, display.size)
        val spread = display[0] as NgMangaSpread
        assertEquals(listOf(second, first), spread.physical)
        assertEquals(0, NgMangaLayout.slot(display, second, 1))
        assertEquals(second, NgMangaLayout.progress(spread, second))
        assertEquals(first, NgMangaLayout.progress(spread, third))
        assertEquals(third, display[1])
    }
    @Test fun prefetchBudgetCountsOriginalsAndZeroMeansNoNetwork() {
        val pages = (0..5).map { page(0, it) }
        val display = NgMangaLayout.display(pages, true, false)
        assertEquals(listOf("0/2", "0/3", "0/4"), NgMangaLayout.prefetch(display, 0, true, 3).network.map { it.resource })
        assertTrue(NgMangaLayout.prefetch(display, 0, true, 0).network.isEmpty())
    }
}
