package io.legado.app.ui.book.manga

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import io.legado.app.integration.manga.NgMangaLayout
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage

/** Owns continuous-reader chapter flips and their scroll/touch state. */
internal class MangaFlipController(
    private val isLoading: () -> Boolean,
    private val currentChapter: () -> Int,
    private val currentPage: () -> MangaPage?,
    private val moveChapter: (Int) -> Boolean,
    private val updatePage: (MangaPage) -> Unit,
    private val preloadNextChapter: () -> Unit,
) {
    private enum class State { IDLE, WAITING_CONTENT }

    private var state = State.IDLE
    private var pendingSourceChapter: Int? = null
    private var lastScrollDirection = 0
    private var dragMultiTouch = false
    private var dragTriggered = false

    fun reset() {
        state = State.IDLE
        pendingSourceChapter = null
        lastScrollDirection = 0
        dragMultiTouch = false
        dragTriggered = false
    }

    fun onVisiblePage(page: BaseMangaPage?, currentChapterVisible: Boolean) {
        if (page == null || isLocked()) return
        val realPage = NgMangaLayout.progress(page, currentPage()) as? MangaPage ?: return
        if (realPage.chapterIndex != currentChapter()) {
            if (currentChapterVisible || !io.legado.features.adapter.MangaReaderFeatures.canCrossChapter(
                    currentChapter(), realPage.chapterIndex, false,
                )
            ) return
            val direction = (realPage.chapterIndex - currentChapter()).compareTo(0)
            requestChapter(direction)
            return
        }
        updatePage(realPage)
    }

    fun onScroll(direction: Int, nearChapterEnd: Boolean) {
        if (direction != 0) lastScrollDirection = direction
        if (nearChapterEnd && !isLocked()) preloadNextChapter()
    }

    fun onTouchDrag(
        total: Offset,
        touchSlop: Float,
        horizontal: Boolean,
        rtl: Boolean,
        scale: Float,
        list: LazyListState,
        pages: List<BaseMangaPage>,
        chapterIndex: Int,
        pointerCount: Int,
    ): Boolean {
        if (pointerCount > 1) dragMultiTouch = true
        if (isLocked() || isLoading() || scale != 1f || dragMultiTouch || dragTriggered) return dragTriggered

        val primary = if (horizontal) total.x else total.y
        val secondary = if (horizontal) total.y else total.x
        if (kotlin.math.abs(primary) <= touchSlop || kotlin.math.abs(primary) <= kotlin.math.abs(secondary)) {
            return false
        }
        val forward = if (horizontal) (primary < 0f) != rtl else primary < 0f
        val atEdge = if (forward) !list.canScrollForward else !list.canScrollBackward
        if (!atEdge || findEdgePage(list, pages, chapterIndex, forward) == null) return false

        val moved = requestChapter(if (forward) 1 else -1)
        if (moved) {
            dragTriggered = true
        }
        return dragTriggered
    }

    fun onTouchEnd() {
        dragMultiTouch = false
        dragTriggered = false
    }

    fun onContentReady(chapterIndex: Int, loading: Boolean) {
        if (!loading && state == State.WAITING_CONTENT && pendingSourceChapter != chapterIndex) {
            state = State.IDLE
            pendingSourceChapter = null
            lastScrollDirection = 0
        }
    }

    fun onLoadFail() = reset()

    private fun findEdgePage(
        list: LazyListState,
        pages: List<BaseMangaPage>,
        chapterIndex: Int,
        forward: Boolean,
    ): MangaPage? {
        val info = list.layoutInfo
        return info.visibleItemsInfo.asSequence()
            .filter { it.offset < info.viewportEndOffset && it.offset + it.size > info.viewportStartOffset }
            .flatMap { visible ->
                NgMangaLayout.realPages(pages.getOrNull(visible.index) ?: return@flatMap emptySequence())
                    .asSequence()
            }
            .firstOrNull { page ->
                page.chapterIndex == chapterIndex && page.imageCount > 0 &&
                    page.index == if (forward) page.imageCount - 1 else 0
            }
    }

    private fun requestChapter(direction: Int): Boolean {
        if (isLocked()) return false
        val sourceChapter = currentChapter()
        val moved = moveChapter(direction)
        if (moved) {
            state = State.WAITING_CONTENT
            pendingSourceChapter = sourceChapter
        }
        return moved
    }

    private fun isLocked() = state == State.WAITING_CONTENT || isLoading()
}
