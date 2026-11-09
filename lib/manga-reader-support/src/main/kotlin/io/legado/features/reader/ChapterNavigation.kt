package io.legado.features.reader

import io.legado.features.api.manga.ChapterLanding
import io.legado.features.api.manga.ChapterMove

/** Never commits chapter/progress until actual target content supplies its image count. */
class ChapterNavigation(private val maxEmptyVolumes: Int = 32) {
    private var pending: ChapterMove? = null
    private var first = false
    private var sequence = 0L
    private var skipped = 0
    init { require(maxEmptyVolumes > 0) }
    @Synchronized fun request(current: Int, total: Int, direction: Int, toFirst: Boolean): ChapterMove? {
        require(direction == -1 || direction == 1)
        if (pending != null || current + direction !in 0 until total) return null
        first = toFirst || direction > 0
        skipped = 0
        return ChapterMove(current + direction, direction, ++sequence).also { pending = it }
    }
    @Synchronized fun ready(chapter: Int, imageCount: Int, volume: Boolean = false): ChapterLanding? {
        val request = pending ?: return null
        if (request.chapter != chapter || imageCount <= 0) return null
        pending = null
        return ChapterLanding(chapter, if (first || imageCount == 0) 0 else imageCount - 1, request.revision)
    }
    /** Empty volume headings are traversed without publishing an intermediate reading position. */
    @Synchronized fun skipEmpty(chapter: Int, total: Int): ChapterMove? {
        val request = pending ?: return null
        if (request.chapter != chapter) return null
        val next = chapter + request.direction
        if (++skipped > maxEmptyVolumes || next !in 0 until total) {
            pending = null
            return null
        }
        return request.copy(chapter = next).also { pending = it }
    }
    @Synchronized fun target(): Int? = pending?.chapter
    @Synchronized fun fail(chapter: Int) { if (pending?.chapter == chapter) pending = null }
    @Synchronized fun reset() { pending = null; sequence++ }
}

object ChapterBoundaryPolicy {
    fun accept(current: Int, candidate: Int, currentChapterVisible: Boolean): Boolean =
        current == candidate || !currentChapterVisible && kotlin.math.abs(candidate.toLong() - current) == 1L
}
