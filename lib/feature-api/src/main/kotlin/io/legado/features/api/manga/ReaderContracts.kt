package io.legado.features.api.manga

data class ReaderSessionKey(val bookId: String, val sourceIdentity: String, val epoch: Long)
data class PageKey(val chapter: String, val page: Int, val resource: String)
data class ReaderPage(val key: PageKey, val chapterIndex: Int, val imageCount: Int)
data class DisplayItem(val pages: List<ReaderPage>, val left: ReaderPage?, val right: ReaderPage?) {
    init { require(pages.size in 1..2) }
    fun progress(direction: Int): ReaderPage = if (direction > 0) pages.last() else pages.first()
}
data class SpreadPlan(val items: List<DisplayItem?>, val originalToDisplay: Map<PageKey, Int>)
data class ReaderPrefetchPlan(val network: List<PageKey>, val metadata: List<PageKey>)
data class ChapterMove(val chapter: Int, val direction: Int, val revision: Long)
data class ChapterLanding(val chapter: Int, val page: Int, val revision: Long)
