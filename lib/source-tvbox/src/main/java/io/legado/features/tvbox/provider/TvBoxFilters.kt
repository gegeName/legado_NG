package io.legado.features.tvbox.provider

import com.google.gson.JsonObject
import io.legado.features.api.source.*
import io.legado.features.tvbox.config.text

/** Category-specific fields retain raw protocol values instead of sending their display labels. */
internal class TvBoxFilters(override val source: SourceKey, home: JsonObject) : FilterSession {
    private data class Field(val key: String, val title: String, val choices: List<Pair<String, String>>, val initial: Int)
    private val categories = home.getAsJsonArray("class")?.map { it.asJsonObject.let { c -> c.text("type_id") to c.text("type_name") } }.orEmpty()
    private val fields = categories.map { category ->
        home["filters"]?.takeIf { it.isJsonObject }?.asJsonObject?.get(category.first)?.takeIf { it.isJsonArray }?.asJsonArray?.map { element ->
            val field = element.asJsonObject
            val choices = field.getAsJsonArray("value")?.map { it.asJsonObject.let { choice -> choice.text("v") to choice.text("n") } }.orEmpty()
            Field(field.text("key"), field.text("name"), choices, choices.indexOfFirst { it.first == field.text("init") }.coerceAtLeast(0))
        }.orEmpty()
    }
    private var category = 0
    private var closed = false
    private val values = mutableMapOf<List<Int>, FilterValue>()
    override fun describe(): List<FilterDescriptor> {
        check(!closed)
        if (categories.isEmpty()) return emptyList()
        return listOf(FilterDescriptor(listOf(0), "分类", FilterKind.SELECT, FilterValue(selected = category), categories.map { it.second })) +
            fields[category].mapIndexed { index, field ->
                val path = listOf(1, category, index)
                FilterDescriptor(path, field.title, FilterKind.SELECT, values[path] ?: FilterValue(selected = field.initial), field.choices.map { it.second })
            }
    }
    override fun set(path: List<Int>, value: FilterValue) {
        val field = describe().firstOrNull { it.path == path } ?: error("TVBox 筛选字段已变化")
        require(value.selected in field.choices.indices) { "TVBox 筛选值无效" }
        if (path == listOf(0)) category = value.selected else values[path] = value
    }
    fun category(): String { check(!closed); return categories.getOrNull(category)?.first ?: error("TVBox 分类为空") }
    fun extend(): Map<String, String> {
        check(!closed)
        return fields.getOrNull(category).orEmpty().mapIndexedNotNull { index, field ->
            field.choices.getOrNull((values[listOf(1, category, index)] ?: FilterValue(selected = field.initial)).selected)?.let { field.key to it.first }
        }.toMap()
    }
    override fun close() { closed = true; values.clear() }
}
