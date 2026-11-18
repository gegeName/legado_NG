package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.entities.MangaPage
import org.junit.Assert.*
import org.junit.Test

class MangaPageTurnTest {
    private val first = MangaPage(chapterIndex = 3, chapterSize = 10, index = 0, imageCount = 2)
    private val second = first.copy(index = 1)
    private val pages = listOf(first, second)

    @Test fun retainedHandlerUsesTheCurrentPageForEveryForwardAndReverseTurn() {
        val chapter = (0..5).map { first.copy(index = it, imageCount = 6) }
        var position = 0
        val handler = { direction: Int -> mangaBeginTurn(chapter, position, direction, 4) }
        for (direction in listOf(1, 1, 1, 1, -1, -1, 1, -1)) {
            val pending = requireNotNull(handler(direction))
            assertEquals("3:$position", pending.from)
            assertEquals("3:${position + direction}", pending.to)
            assertTrue(mangaCanCommit(pending, mangaItemKey(chapter, position), chapter, 4))
            position += direction
            assertFalse(mangaCanCommit(pending, mangaItemKey(chapter, position), chapter, 4))
        }
        assertEquals(2, position)
    }

    @Test fun retainedHandlerResolvesJumpAndPrependedContentAtTheNextGesture() {
        var content = pages
        var position = 0
        var revision = 4
        val handler = { mangaBeginTurn(content, position, -1, revision) }
        val oldKey = mangaItemKey(content, position)
        position = 1; revision++
        content = listOf(first.copy(chapterIndex = 2)) + content
        position++
        val pending = requireNotNull(handler())
        assertEquals("3:1", pending.from)
        assertEquals("3:0", pending.to)
        assertSame(content, pending.pages)
        assertEquals(5, pending.jumpRevision)
        assertTrue(mangaCanCommit(pending, "3:1", content, 5))
        assertFalse(mangaCanCommit(pending.copy(from = oldKey), "3:1", content, 5))
    }

    @Test fun beginningAtTheEdgesCannotWrapOrSkipTheTail() {
        assertNull(mangaBeginTurn(pages, 0, -1, 4))
        assertNull(mangaBeginTurn(pages, pages.size, 1, 4))
        assertNull(mangaBeginTurn(pages, 0, 2, 4))
        val tail = requireNotNull(mangaBeginTurn(pages, 1, 1, 4))
        assertEquals(mangaTailKey(pages), tail.to)
        assertTrue(mangaCanCommit(tail, "3:1", pages, 4))
        assertEquals("3:1", requireNotNull(mangaBeginTurn(pages, pages.size, -1, 4)).to)
    }

    @Test fun completedTurnCommitsOnceAndAJumpInvalidatesTheSameTarget() {
        val turn = MangaPageTurn("3:0", "3:1", 1, pages, 4)
        assertTrue(mangaCanCommit(turn, "3:0", pages, 4))
        assertFalse(mangaCanCommit(turn, "3:1", pages, 4))
        assertFalse(mangaCanCommit(turn, "3:0", pages, 5))
    }

    @Test fun refreshedImagesInvalidateFramesEvenWhenTheirContentKeysMatch() {
        val turn = MangaPageTurn("3:0", "3:1", 1, pages, 4)
        assertFalse(mangaCanCommit(turn, "3:0", listOf(first, second.copy(mImageUrl = "replacement")), 4))
        assertFalse(mangaCanCommit(turn, "3:0", listOf(first), 4))
    }

    @Test fun prependedChapterKeepsImageIdentityAndAppendedContentRebasesTheTail() {
        val oldTail = mangaTailKey(pages)
        val next = first.copy(chapterIndex = 4)
        assertEquals(2, mangaAnchorIndex(listOf(next.copy(chapterIndex = 2), first, second), "3:1", 1))
        assertEquals(2, mangaAnchorIndex(pages + next, oldTail, 2))
        assertEquals(1, mangaAnchorIndex(listOf(first), "removed", 10))
    }

    @Test fun reversedReleaseCancelsDespiteDistanceAndSmallFlingDoesNotTurn() {
        assertFalse(mangaFinishTurn(0.5f, -800f, 600f))
        assertFalse(mangaFinishTurn(0.01f, 1200f, 600f))
        assertFalse(mangaFinishTurn(0.1f, 0f, 600f))
        assertTrue(mangaFinishTurn(0.1f, 800f, 600f))
        assertTrue(mangaFinishTurn(0.3f, 0f, 600f))
    }

    @Test fun rtlOnlyReversesHorizontalMovementAndNeverChapterOrder() {
        assertEquals(-1, mangaTurnSign(true, false, 1))
        assertEquals(1, mangaTurnSign(true, true, 1))
        assertEquals(-1, mangaTurnSign(false, true, 1))
        assertEquals(1, mangaTurnSign(false, true, -1))
    }

    @Test fun continuousViewportReportsBothAdjacentChaptersBeforeCommitting() {
        val visible = mutableListOf<MangaPage>()
        val moves = mutableListOf<Int>()
        val saved = mutableListOf<MangaPage>()
        val controller = MangaFlipController(
            isLoading = { false }, currentChapter = { first.chapterIndex },
            renderedChapter = { first.chapterIndex }, currentPage = { first },
            moveChapter = { moves += it; true }, updatePage = { saved += it },
            preloadNextChapter = {}, onViewportPage = { visible += it },
        )
        val next = first.copy(chapterIndex = first.chapterIndex + 1)
        val previous = second.copy(chapterIndex = first.chapterIndex - 1)
        controller.onVisiblePage(next, currentChapterVisible = false, scrolling = true)
        controller.onVisiblePage(previous, currentChapterVisible = false, scrolling = true)

        assertEquals(listOf(next, previous), visible)
        assertTrue(saved.isEmpty())
        assertTrue(moves.isEmpty())

        controller.onVisiblePage(previous, currentChapterVisible = false, scrolling = false)
        assertEquals(listOf(-1), moves)
    }
}
