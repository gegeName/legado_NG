package io.legado.features.mihon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.features.api.source.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MangaFilters(override val source: SourceKey, private val filters: FilterList) : FilterSession {
    private val requestLock = Mutex()
    private var closed = false
    private fun checkOpen() { check(!closed) { "筛选会话已关闭" } }
    suspend fun <T> use(block: suspend (FilterList) -> T): T = requestLock.withLock { checkOpen(); block(filters) }
    @Synchronized override fun close() { closed = true }
    @Synchronized override fun describe(): List<FilterDescriptor> { checkOpen(); return describe(filters, emptyList()) }
    private fun describe(items: List<*>, parent: List<Int>): List<FilterDescriptor> = items.mapIndexedNotNull { index, value ->
        val path = parent + index
        val filter = value as? Filter<*> ?: return@mapIndexedNotNull null
        when (filter) {
            is Filter.Header -> FilterDescriptor(path, filter.name, FilterKind.HEADER)
            is Filter.Separator -> FilterDescriptor(path, filter.name, FilterKind.SEPARATOR)
            is Filter.Text -> FilterDescriptor(path, filter.name, FilterKind.TEXT, FilterValue(text = filter.state))
            is Filter.CheckBox -> FilterDescriptor(path, filter.name, FilterKind.CHECKBOX, FilterValue(checked = filter.state))
            is Filter.TriState -> FilterDescriptor(path, filter.name, FilterKind.TRISTATE, FilterValue(selected = filter.state))
            is Filter.Select<*> -> FilterDescriptor(path, filter.name, FilterKind.SELECT, FilterValue(selected = filter.state), filter.values.map { it.toString() })
            is Filter.Sort -> FilterDescriptor(path, filter.name, FilterKind.SORT, FilterValue(selected = filter.state?.index ?: -1, ascending = filter.state?.ascending ?: true), filter.values.toList())
            is Filter.Group<*> -> FilterDescriptor(path, filter.name, FilterKind.GROUP, children = describe(filter.state, path))
        }
    }
    @Synchronized override fun set(path: List<Int>, value: FilterValue) {
        checkOpen()
        check(requestLock.tryLock()) { "筛选请求进行中" }
        try {
        require(path.isNotEmpty())
        var items: List<*> = filters
        path.dropLast(1).forEach { items = (items[it] as Filter.Group<*>).state }
        when (val filter = items[path.last()]) {
            is Filter.Text -> filter.state = value.text
            is Filter.CheckBox -> filter.state = value.checked
            is Filter.TriState -> { require(value.selected in 0..2); filter.state = value.selected }
            is Filter.Select<*> -> { require(value.selected in filter.values.indices); filter.state = value.selected }
            is Filter.Sort -> { require(value.selected in -1 until filter.values.size); filter.state = value.selected.takeIf { it >= 0 }?.let { Filter.Sort.Selection(it, value.ascending) } }
            else -> error("此筛选项不可编辑")
        }
        } finally { requestLock.unlock() }
    }
}
