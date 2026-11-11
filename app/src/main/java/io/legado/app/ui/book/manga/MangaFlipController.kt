package io.legado.app.ui.book.manga

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import io.legado.app.integration.manga.NgMangaLayout
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading

/** Owns continuous-reader chapter flips and their scroll/touch state. */
internal class MangaFlipController(
    private val isLoading: () -> Boolean,
    private val currentChapter: () -> Int,
    private val renderedChapter: () -> Int,
    private val currentPage: () -> MangaPage?,
    private val moveChapter: (Int) -> Boolean,
    private val updatePage: (MangaPage) -> Unit,
    private val preloadNextChapter: () -> Unit,
) {
    private enum class State { IDLE, WAITING_CONTENT }

    private var state = State.IDLE
    private var pendingSourceChapter: Int? = null
    private var dragMultiTouch = false
    private var dragTriggered = false

    fun reset() {
        state = State.IDLE
        pendingSourceChapter = null
        dragMultiTouch = false
        dragTriggered = false
    }

    fun onVisiblePage(page: BaseMangaPage?, currentChapterVisible: Boolean) {
        if (page == null) return
        val realPage = NgMangaLayout.progress(page, currentPage())
        if (realPage !is MangaPage && (realPage !is ReaderLoading || !realPage.isVolume)) return
        // Content arrival alone cannot unlock an edge drag: the source chapter
        // can still occupy the viewport while the target is appended below it.
        if (realPage is MangaPage && realPage.chapterIndex == renderedChapter()) {
            onContentReady(realPage.chapterIndex, isLoading())
        }
        if (isLocked()) return
        if (realPage.chapterIndex != currentChapter()) {
            if (currentChapterVisible || !io.legado.features.adapter.MangaReaderFeatures.canCrossChapter(
                    currentChapter(), realPage.chapterIndex, false,
                )
            ) return
            val direction = (realPage.chapterIndex - currentChapter()).compareTo(0)
            requestChapter(direction)
            return
        }
        if (realPage is MangaPage) updatePage(realPage)
    }

    fun onNearChapterEnd(nearChapterEnd: Boolean) {
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

    private fun onContentReady(chapterIndex: Int, loading: Boolean) {
        if (!loading && state == State.WAITING_CONTENT && pendingSourceChapter != chapterIndex &&
            chapterIndex == currentChapter()
        ) {
            state = State.IDLE
            pendingSourceChapter = null
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
                NgMangaLayout.realPages(NgMangaLayout.measuredPage(pages, visible.index, visible.key) ?: return@flatMap emptySequence())
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
        // A cached target can commit synchronously and re-enter callbacks. Lock first.
        state = State.WAITING_CONTENT
        pendingSourceChapter = sourceChapter
        return try {
            moveChapter(direction).also { if (!it) reset() }
        } catch (error: Throwable) {
            reset()
            throw error
        }
    }

    private fun isLocked() = state == State.WAITING_CONTENT || isLoading() || renderedChapter() != currentChapter()
}
