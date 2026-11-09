package io.legado.features.tvbox.sniff

import io.legado.features.api.source.VideoResource
import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.config.*
import io.legado.features.tvbox.provider.TvBoxJson
import kotlinx.coroutines.*

fun interface SniffPort {
    suspend fun sniff(url: String, headers: Map<String, String>, timeoutMs: Long): VideoResource
}
class VideoResolver(private val http: TvBoxHttp, private val sniff: SniffPort) {
    private fun mediaUrl(url: String): String = url.trim().also {
        require(java.net.URI(it).scheme?.lowercase() in setOf("http", "https", "rtmp", "rtmps", "rtsp", "rtsps", "file", "proxy")) { "TVBox 未返回支持的媒体地址" }
    }
    suspend fun resolve(config: TvBoxConfig, site: TvBoxSite, json: String, flag: String = "",
        aggregate: suspend (TvBoxParse, List<TvBoxParse>, String, String) -> String = { _, _, _, _ -> error("站点未提供 JAR 聚合解析能力") }): VideoResource {
        val result = TvBoxJson.root(json)
        val url = result.text("url")
        require(url.isNotBlank()) { "TVBox 播放结果缺少 url" }
        val headers = TvBoxConfigs.mergeHeaders(site.headers, TvBoxConfigs.headers(result["header"]))
        val needsParse = result.int("parse") != 0 || result.int("jx") != 0
        if (!needsParse) return VideoResource(mediaUrl(url), "默认", headers, preferred = true)
        val configured = result.text("playUrl").ifBlank { site.playUrl }
        val candidates = if (configured.isNotBlank()) listOf(TvBoxParse("站点解析", configured, 0)) else config.parses.sortedByDescending { flag.isNotBlank() && flag in it.flags }
        var failure: Exception? = null
        return withTimeout(site.timeoutSeconds * 1000L) {
            for (parse in candidates) {
                currentCoroutineContext().ensureActive()
                try {
                    val merged = TvBoxConfigs.mergeHeaders(headers, parse.headers)
                    when (parse.type) {
                        0 -> return@withTimeout sniff.sniff(parse.url + url, merged, site.timeoutSeconds * 1000L)
                        1 -> {
                            val parsed = http.bound { TvBoxJson.root(http.text(parse.extendedUrl() + url, merged)) }
                            val data = parsed["data"]?.takeIf { it.isJsonObject }?.asJsonObject ?: parsed
                            val media = data.text("url")
                            return@withTimeout VideoResource(mediaUrl(media), parse.name, TvBoxConfigs.mergeHeaders(merged, TvBoxConfigs.headers(data["header"])), preferred = true)
                        }
                        2, 3 -> {
                            val parsed = TvBoxJson.root(aggregate(parse, config.parses, flag, url))
                            val media = parsed.text("url").trim()
                            require(media.isNotBlank()) { "JAR 聚合解析缺少 url" }
                            val mediaHeaders = TvBoxConfigs.mergeHeaders(merged, TvBoxConfigs.headers(parsed["header"]))
                            if (parsed.int("parse") != 0 || parsed.int("jx") != 0) return@withTimeout sniff.sniff(media, mediaHeaders, site.timeoutSeconds * 1000L)
                            return@withTimeout VideoResource(mediaUrl(media), parse.name, mediaHeaders, preferred = true)
                        }
                        else -> throw UnsupportedOperationException("未支持的 TVBox 解析类型 ${parse.type}")
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
            }
            if (candidates.isEmpty()) return@withTimeout sniff.sniff(url, headers, site.timeoutSeconds * 1000L)
            throw failure ?: IllegalStateException("TVBox 无可用解析器")
        }
    }
}
