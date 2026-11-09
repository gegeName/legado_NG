package io.legado.features.runtime

import io.legado.features.api.host.ConfigPort
import io.legado.features.api.source.*
import kotlinx.serialization.json.*

/** Persist values, never ABI filter objects. Each request opens its own live session. */
class SourceFilterStore(private val config: ConfigPort) {
    private fun key(source: SourceKey) = "${source.provider}:${source.namespace}:${source.nativeId}"
    fun apply(session: FilterSession): FilterSession {
        val raw = config.get("source-filters", key(session.source)) ?: return session
        require(raw.length <= 1024 * 1024) { "来源筛选设置过大" }
        val values = Json.parseToJsonElement(raw).jsonArray
        val pending = values.map { it.jsonObject }.sortedBy { it["path"]!!.jsonArray.size }
        // Category selection can change the remaining descriptors; rediscover after each field.
        for (entry in pending) {
            val path = entry["path"]!!.jsonArray.map { it.jsonPrimitive.int }
            fun fields(list: List<FilterDescriptor>): List<FilterDescriptor> = list.flatMap { listOf(it) + fields(it.children) }
            val field = fields(session.describe()).firstOrNull { it.path == path } ?: continue
            val selected = entry["selected"]!!.jsonPrimitive.int
            if (field.choices.isNotEmpty() && selected !in field.choices.indices) continue
            session.set(path, FilterValue(entry["text"]!!.jsonPrimitive.content, selected, entry["checked"]!!.jsonPrimitive.boolean, entry["ascending"]!!.jsonPrimitive.boolean))
        }
        return session
    }
    fun save(session: FilterSession) {
        fun fields(list: List<FilterDescriptor>): List<FilterDescriptor> = list.flatMap { if (it.children.isEmpty()) listOf(it) else fields(it.children) }
        val value = buildJsonArray {
            fields(session.describe()).filter { it.kind !in setOf(FilterKind.HEADER, FilterKind.SEPARATOR) }.forEach { field -> add(buildJsonObject {
                put("path", JsonArray(field.path.map(::JsonPrimitive)))
                put("text", field.value.text); put("selected", field.value.selected)
                put("checked", field.value.checked); put("ascending", field.value.ascending)
            }) }
        }.toString()
        require(value.length <= 1024 * 1024)
        config.put("source-filters", key(session.source), value)
    }
}
