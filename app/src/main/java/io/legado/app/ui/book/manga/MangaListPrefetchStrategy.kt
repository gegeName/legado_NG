package io.legado.app.ui.book.manga

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler

@OptIn(ExperimentalFoundationApi::class)
internal class MangaListPrefetchStrategy(
    override val prefetchScheduler: PrefetchScheduler? = null,
) : LazyListPrefetchStrategy {
    private val handles = linkedMapOf<Int, LazyLayoutPrefetchState.PrefetchHandle>()
    private var forward = true

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        update(layoutInfo)
    }

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        if (delta == 0f) return
        forward = delta < 0
        update(layoutInfo)
        val edge = if (forward) layoutInfo.visibleItemsInfo.lastOrNull()
            else layoutInfo.visibleItemsInfo.firstOrNull()
        if (edge != null) {
            val distance = if (forward) edge.offset + edge.size + layoutInfo.mainAxisItemSpacing - layoutInfo.viewportEndOffset
                else layoutInfo.viewportStartOffset - edge.offset
            if (distance < kotlin.math.abs(delta)) handles[edge.index + if (forward) 1 else -1]?.markAsUrgent()
        }
    }

    private fun LazyListPrefetchScope.update(layoutInfo: LazyListLayoutInfo) {
        val first = layoutInfo.visibleItemsInfo.firstOrNull()?.index
        val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (first == null || last == null) { clear(); return }
        val upcoming = buildList {
            repeat(2) { offset -> add(if (forward) last + offset + 1 else first - offset - 1) }
            add(if (forward) first - 1 else last + 1)
        }.filter { it in 0 until layoutInfo.totalItemsCount }
        val iterator = handles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in upcoming) { entry.value.cancel(); iterator.remove() }
        }
        upcoming.forEach { index -> if (index !in handles) handles[index] = schedulePrefetch(index) }
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        repeat(2) { schedulePrefetch(firstVisibleItemIndex + it) }
    }

    fun clear() {
        handles.values.forEach { it.cancel() }
        handles.clear()
    }
}
