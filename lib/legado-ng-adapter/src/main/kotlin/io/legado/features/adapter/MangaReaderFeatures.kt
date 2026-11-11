package io.legado.features.adapter

import io.legado.features.api.host.ConfigPort
import io.legado.features.api.manga.*
import io.legado.features.reader.*

/** Public reader facade; host maps NG pages without depending on internal feature classes. */
object MangaReaderFeatures {
    data class Preferences(val chapterAhead: Int, val chapterBehind: Int, val imageChapters: Int, val doublePage: Boolean = false)
    fun preferences(config: ConfigPort, legacy: Int) = ReaderPreferenceStore(config).load(legacy).let { Preferences(it.chapterAhead, it.chapterBehind, it.imageChapters, it.doublePage) }
    fun savePreferences(config: ConfigPort, value: Preferences) = ReaderPreferenceStore(config).save(ReaderPreferences(value.chapterAhead, value.chapterBehind, value.imageChapters, value.doublePage))
    fun chapters(current: Int, total: Int, ahead: Int, behind: Int) = ChapterWindowPlanner.plan(current, total, ahead, behind)
    fun prefetch(pages: List<ReaderPage?>, visible: Int, forward: Boolean, count: Int): ReaderPrefetchPlan =
        ImagePrefetchPlanner.plan(SpreadPlanner.plan(pages, false, false).items, visible, forward, count, budget = 24)
    fun prefetchItems(items: List<DisplayItem?>, visible: Int, forward: Boolean, count: Int) =
        ImagePrefetchPlanner.plan(items, visible, forward, count, budget = 24)
    fun spreads(pages: List<ReaderPage?>, enabled: Boolean, rtl: Boolean) = SpreadPlanner.plan(pages, enabled, rtl)
    fun canCrossChapter(current: Int, candidate: Int, currentVisible: Boolean) = ChapterBoundaryPolicy.accept(current, candidate, currentVisible)
    data class VisibleItem(val key: String, val chapter: Int, val offset: Int, val size: Int)
    fun visiblePage(current: Int, items: List<VisibleItem>, start: Int, end: Int, center: Float): String? =
        ChapterViewportPolicy.select(current, items.map { ChapterViewportItem(it.key, it.chapter, it.offset, it.size) }, start, end, center)
    class Navigation {
        private val delegate = ChapterNavigation()
        fun request(current: Int, total: Int, direction: Int, first: Boolean) = delegate.request(current, total, direction, first)
        fun ready(chapter: Int, count: Int, volume: Boolean = false) = delegate.ready(chapter, count, volume)
        fun skipEmpty(chapter: Int, total: Int) = delegate.skipEmpty(chapter, total)
        fun target() = delegate.target()
        fun fail(chapter: Int) = delegate.fail(chapter)
        fun reset() = delegate.reset()
    }
    class Anchor {
        private val delegate = AnchorPolicy()
        private var active = false
        fun begin(key: PageKey, revision: Long, now: Long) { active = true; delegate.begin(key, revision, now) }
        fun accept(key: PageKey, revision: Long, now: Long) = !active || delegate.accept(key, revision, now)
    }
    class ImageWindow : AutoCloseable {
        private val delegate = ChapterImageWindow()
        fun update(key: ReaderSessionKey, current: Int, total: Int, count: Int, prefetch: suspend (Int) -> Boolean, report: (Int, Throwable) -> Unit) = delegate.update(key, current, total, count, prefetch, report)
        fun isComplete(key: ReaderSessionKey, chapter: Int) = delegate.isComplete(key, chapter)
        fun clear() = delegate.clear()
        override fun close() = delegate.close()
    }
}
