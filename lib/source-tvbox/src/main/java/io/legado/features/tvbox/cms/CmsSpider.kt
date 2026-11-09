package io.legado.features.tvbox.cms

import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.config.TvBoxSite
import io.legado.features.tvbox.provider.SpiderPort
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl

class CmsSpider(private val site: TvBoxSite, private val http: TvBoxHttp) : SpiderPort {
    private fun get(vararg query: Pair<String, String>): String {
        val url = site.api.toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return http.text(url.toString(), site.headers)
    }
    override fun home(filter: Boolean) = get("ac" to "list")
    override fun category(id: String, page: Int, extend: Map<String, String>) = get("ac" to "videolist", "t" to id, "pg" to page.toString())
    override fun search(query: String, page: Int) = get("ac" to "videolist", "wd" to query, "pg" to page.toString())
    override fun detail(id: String) = get("ac" to "detail", "ids" to id)
    override fun play(flag: String, id: String, flags: List<String>): String = Gson().toJson(mapOf("parse" to 0, "url" to id, "header" to site.headers))
}
