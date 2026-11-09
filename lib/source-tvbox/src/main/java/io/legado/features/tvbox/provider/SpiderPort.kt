package io.legado.features.tvbox.provider

interface SpiderPort : AutoCloseable {
    fun home(filter: Boolean): String
    fun category(id: String, page: Int, extend: Map<String, String>): String
    fun search(query: String, page: Int): String
    fun detail(id: String): String
    fun play(flag: String, id: String, flags: List<String>): String
    fun proxy(params: Map<String, String>): Array<Any?>? = null
    fun parse(parser: io.legado.features.tvbox.config.TvBoxParse, all: List<io.legado.features.tvbox.config.TvBoxParse>, flag: String, url: String): String =
        throw UnsupportedOperationException("此站点后端未提供 JAR 聚合解析能力")
    override fun close() {}
}
