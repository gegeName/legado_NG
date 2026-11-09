package io.legado.features.reader

import io.legado.features.api.manga.*

object ChapterWindowPlanner {
    fun plan(current: Int, total: Int, ahead: Int, behind: Int): IntRange {
        require(ahead >= 0 && behind >= 0)
        if (total <= 0 || current !in 0 until total) return IntRange.EMPTY
        return (current.toLong() - behind).coerceAtLeast(0).toInt()..(current.toLong() + ahead).coerceAtMost(total - 1L).toInt()
    }
    fun backgroundImages(current: Int, total: Int, count: Int): IntRange =
        if (count <= 0) IntRange.EMPTY else plan(current, total, count, minOf(count, 5))
}

object ImagePrefetchPlanner {
    /** Indexes are display slots; count/budget limit actual distinct original images. */
    fun plan(items: List<DisplayItem?>, visible: Int, forward: Boolean, count: Int, budget: Int, metadataRadius: Int = 4): ReaderPrefetchPlan {
        require(metadataRadius >= 0)
        if (items.isEmpty() || visible !in items.indices) return ReaderPrefetchPlan(emptyList(), emptyList())
        val limit = count.coerceAtLeast(0).coerceAtMost(budget.coerceAtLeast(0))
        val network = linkedMapOf<String, PageKey>()
        val indices = if (forward) visible + 1..items.lastIndex else visible - 1 downTo 0
        for (index in indices) {
            if (network.size >= limit) break
            val pages = items[index]?.pages ?: continue
            val ordered = if (forward) pages else pages.asReversed()
            for (page in ordered) { if (network.size >= limit) break; network.putIfAbsent(page.key.resource, page.key) }
        }
        val first = (visible.toLong() - metadataRadius).coerceAtLeast(0).toInt()
        val last = (visible.toLong() + metadataRadius).coerceAtMost(items.lastIndex.toLong()).toInt()
        val metadata = (first..last).flatMap { items[it]?.pages.orEmpty() }.map { it.key }.distinct()
        return ReaderPrefetchPlan(network.values.toList(), metadata.distinctBy { it.resource })
    }
}

object SpreadPlanner {
    /** Null is a title/loading marker and keeps its own display slot. No pairing across it. */
    fun plan(pages: List<ReaderPage?>, enabled: Boolean, rtl: Boolean): SpreadPlan {
        val items = mutableListOf<DisplayItem?>()
        val mapping = linkedMapOf<PageKey, Int>()
        var index = 0
        while (index < pages.size) {
            val first = pages[index++]
            if (first == null) { items += null; continue }
            val second = pages.getOrNull(index)?.takeIf { enabled && it.key.chapter == first.key.chapter && it.key.page == first.key.page + 1 }
            if (second != null) index++
            val pair = listOfNotNull(first, second)
            pair.forEach { mapping[it.key] = items.size }
            items += DisplayItem(pair, if (rtl && second != null) second else first, if (rtl && second != null) first else second)
        }
        return SpreadPlan(items, mapping)
    }
}

/** Inputs use logical direction. RTL conversion is performed once by the host. */
class ChapterTransition {
    private var pending: ChapterMove? = null
    private var sequence = 0L
    @Synchronized fun request(current: Int, total: Int, edgePage: ReaderPage, direction: Int, fullyAtEdge: Boolean): ChapterMove? {
        require(direction == -1 || direction == 1)
        if (pending != null || !fullyAtEdge || edgePage.chapterIndex != current || edgePage.imageCount <= 0) return null
        if (direction > 0 && edgePage.key.page != edgePage.imageCount - 1 || direction < 0 && edgePage.key.page != 0) return null
        val target = current + direction
        if (target !in 0 until total) return null
        return ChapterMove(target, direction, ++sequence).also { pending = it }
    }
    @Synchronized fun contentReady(chapter: Int, revision: Long, imageCount: Int): ChapterLanding? {
        val request = pending ?: return null
        if (request.chapter != chapter || request.revision != revision || imageCount <= 0) return null
        pending = null
        return ChapterLanding(chapter, if (request.direction > 0) 0 else imageCount - 1, revision)
    }
    @Synchronized fun fail(revision: Long) { if (pending?.revision == revision) pending = null }
    @Synchronized fun reset() { pending = null; sequence++ }
    @Synchronized fun isWaiting() = pending != null
}

class AnchorPolicy(private val protectionMillis: Long = 1500) {
    init { require(protectionMillis >= 0) }
    private var target: PageKey? = null
    private var revision = -1L
    private var started = 0L
    private var confirmed = false
    @Synchronized fun begin(page: PageKey, revision: Long, now: Long) { target = page; this.revision = revision; started = now; confirmed = false }
    /** Timeout asks the host to retry locating; it never authorizes writing an unrelated page. */
    @Synchronized fun accept(page: PageKey, revision: Long, now: Long): Boolean {
        if (revision != this.revision || target == null) return false
        if (page == target) confirmed = true
        return confirmed && (page == target || now - started >= protectionMillis)
    }
    @Synchronized fun needsRelocation(now: Long) = target != null && !confirmed && now - started >= protectionMillis
}
