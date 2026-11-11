package io.legado.features.reader

/** Measured items use stable identities from the same layout as their coordinates. */
data class ChapterViewportItem(val key: String, val chapter: Int, val offset: Int, val size: Int)

object ChapterViewportPolicy {
    /** Keep reading the current chapter until its last visible item leaves the viewport. */
    fun select(current: Int, items: List<ChapterViewportItem>, start: Int, end: Int, center: Float): String? {
        val visible = items.filter { it.offset < end && it.offset.toLong() + it.size > start }
        val currentItems = visible.filter { it.chapter == current }
        val candidates = currentItems.ifEmpty {
            visible.filter { ChapterBoundaryPolicy.accept(current, it.chapter, false) }
        }
        return candidates.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) }?.key
    }
}
