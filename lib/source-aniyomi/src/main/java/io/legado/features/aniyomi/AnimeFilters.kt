package io.legado.features.aniyomi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import io.legado.features.api.source.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AnimeFilters(override val source: SourceKey, private val filters: AnimeFilterList) : FilterSession {
    private val requestLock = Mutex()
    private var closed = false
    private fun checkOpen() { check(!closed) { "筛选会话已关闭" } }
    suspend fun <T> use(block: suspend (AnimeFilterList) -> T): T = requestLock.withLock { checkOpen(); block(filters) }
    @Synchronized override fun close() { closed = true }
    @Synchronized override fun describe(): List<FilterDescriptor> { checkOpen(); return describe(filters, emptyList()) }
    private fun describe(items: List<*>, parent: List<Int>): List<FilterDescriptor> = items.mapIndexedNotNull { index, value ->
        val path = parent + index
        val filter = value as? AnimeFilter<*> ?: return@mapIndexedNotNull null
        when (filter) {
            is AnimeFilter.Header -> FilterDescriptor(path, filter.name, FilterKind.HEADER)
            is AnimeFilter.Separator -> FilterDescriptor(path, filter.name, FilterKind.SEPARATOR)
            is AnimeFilter.Text -> FilterDescriptor(path, filter.name, FilterKind.TEXT, FilterValue(text = filter.state))
            is AnimeFilter.CheckBox -> FilterDescriptor(path, filter.name, FilterKind.CHECKBOX, FilterValue(checked = filter.state))
            is AnimeFilter.TriState -> FilterDescriptor(path, filter.name, FilterKind.TRISTATE, FilterValue(selected = filter.state))
            is AnimeFilter.Select<*> -> FilterDescriptor(path, filter.name, FilterKind.SELECT, FilterValue(selected = filter.state), filter.values.map { it.toString() })
            is AnimeFilter.Sort -> FilterDescriptor(path, filter.name, FilterKind.SORT, FilterValue(selected = filter.state?.index ?: -1, ascending = filter.state?.ascending ?: true), filter.values.toList())
            is AnimeFilter.Group<*> -> FilterDescriptor(path, filter.name, FilterKind.GROUP, children = describe(filter.state, path))
        }
    }
    @Synchronized override fun set(path: List<Int>, value: FilterValue) {
        checkOpen()
        check(requestLock.tryLock()) { "筛选请求进行中" }
        try {
        require(path.isNotEmpty())
        var items: List<*> = filters
        path.dropLast(1).forEach { items = (items[it] as AnimeFilter.Group<*>).state }
        when (val filter = items[path.last()]) {
            is AnimeFilter.Text -> filter.state = value.text
            is AnimeFilter.CheckBox -> filter.state = value.checked
            is AnimeFilter.TriState -> { require(value.selected in 0..2); filter.state = value.selected }
            is AnimeFilter.Select<*> -> { require(value.selected in filter.values.indices); filter.state = value.selected }
            is AnimeFilter.Sort -> { require(value.selected in -1 until filter.values.size); filter.state = value.selected.takeIf { it >= 0 }?.let { AnimeFilter.Sort.Selection(it, value.ascending) } }
            else -> error("此筛选项不可编辑")
        }
        } finally { requestLock.unlock() }
    }
}
