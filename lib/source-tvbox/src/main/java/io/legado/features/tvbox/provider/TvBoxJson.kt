package io.legado.features.tvbox.provider

import com.google.gson.*
import io.legado.features.api.source.*
import io.legado.features.tvbox.config.*

data class Episode(val line: String, val url: String)
object TvBoxJson {
    private val gson = Gson()
    fun root(text: String): JsonObject {
        require(text.toByteArray().size <= TvBoxConfigs.MAX_CONFIG) { "Spider 结果过大" }
        return JsonParser.parseString(text).asJsonObject
    }
    fun items(text: String, fallbackId: String? = null): List<CatalogItem> {
        val list = root(text).getAsJsonArray("list") ?: return emptyList()
        return list.map { element ->
            val item = element.asJsonObject
            val id = item.text("vod_id").ifBlank {
                fallbackId.takeIf { list.size() == 1 }.orEmpty()
            }
            require(id.isNotBlank()) { "TVBox 作品缺少 vod_id" }
            CatalogItem(id, item.text("vod_name"), item.text("vod_actor"), item.text("vod_pic"),
                item.text("vod_content"), item.text("vod_class"), item.toString())
        }
    }
    fun page(text: String, page: Int): CatalogPage {
        val root = root(text)
        val count = root.int("pagecount", -1)
        val items = items(text)
        return CatalogPage(items, if (count >= 0) page < count else items.size >= root.int("limit", 20).coerceAtLeast(1))
    }
    fun chapters(item: CatalogItem): List<ChapterDescriptor> {
        val root = root(checkNotNull(item.metadata) { "缺少 TVBox 详情，请重新获取" })
        val lines = root.text("vod_play_from").split("$$$")
        val groups = root.text("vod_play_url").split("$$$")
        var index = 0
        return groups.flatMapIndexed { line, group ->
            val lineName = lines.getOrNull(line).orEmpty().ifBlank { "线路 ${line + 1}" }
            val heading = if (groups.size > 1) listOf(ChapterDescriptor("tvbox-volume:${java.net.URLEncoder.encode(lineName, "UTF-8")}:$line", lineName, index++, volume = true)) else emptyList()
            heading + group.split('#').filter { it.isNotBlank() }.map { entry ->
                val split = entry.indexOf('$')
                val url = if (split < 0) entry else entry.substring(split + 1)
                require(url.isNotBlank()) { "TVBox 剧集地址为空" }
                val episode = Episode(lineName, url)
                val name = if (split < 0) "第 ${index + 1} 集" else entry.substring(0, split)
                val metadata = gson.toJson(episode)
                ChapterDescriptor("tvbox-episode:" + java.net.URLEncoder.encode(metadata, "UTF-8"), "【${episode.line}】$name", index++, metadata = metadata)
            }
        }
    }
    fun episode(chapter: ChapterDescriptor): Episode = gson.fromJson(chapter.metadata ?: java.net.URLDecoder.decode(chapter.id.removePrefix("tvbox-episode:"), "UTF-8"), Episode::class.java)
}
