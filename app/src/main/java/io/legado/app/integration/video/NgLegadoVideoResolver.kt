package io.legado.app.integration.video

import io.legado.app.constant.AppLog
import io.legado.app.help.http.BackstageWebView
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.features.adapter.WebVideoFeatures
import io.legado.features.api.source.VideoResource

/** Host ports for the reference resolver: keep rule JS, decryption, cookies and HTTP options in NG. */
internal object NgLegadoVideoResolver {
    suspend fun resolve(analyze: AnalyzeUrl, input: String, baseUrl: String, html: String?,
        headers: Map<String, String>): VideoResource {
        val source = analyze.getSource()
        WebVideoFeatures.pluginSource(input)?.let { return it }
        val candidates = WebVideoFeatures.candidates(input)
        AppLog.put("视频播放：规则解析得到 ${candidates.size} 个播放候选")
        for ((index, candidate) in candidates.withIndex()) {
            val legacy = WebVideoFeatures.legacySource(candidate)
            val request = if (legacy.url == analyze.url || legacy.url == analyze.videoRuleInput()) analyze
                else analyze.forVideoUrl(legacy.url, headers + legacy.headers)
            val requestHeaders = linkedMapOf<String, String>().apply {
                putAll(headers); putAll(request.getRequestHeaders()); putAll(legacy.headers)
            }
            request.headerMap.putAll(requestHeaders)
            val pageBase = request.url.takeIf(WebVideoFeatures::isNetwork) ?: baseUrl
            val document = html ?: WebVideoFeatures.inlineHtml(request.url)
            val resolved = WebVideoFeatures.resolveLegado(html ?: request.url, requestHeaders,
                probe = {
                    request.getResponseAwait().use { response ->
                        val type = response.body.contentType()?.toString().orEmpty()
                        val text = if (type.contains("text/", true) || type.contains("json", true) ||
                            type.contains("xml", true) || type.contains("javascript", true))
                            response.peekBody(256L * 1024).string().trim() else ""
                        WebVideoFeatures.Probe(response.request.url.toString(), type, text,
                            response.request.headers.toMap(), response.isSuccessful)
                    }
                },
                sniff = {
                    BackstageWebView(url = if (document == null) request.url else pageBase,
                        html = document, tag = source?.getKey(), source = source, headerMap = HashMap(requestHeaders),
                        sourceRegex = WebVideoFeatures.resourceRegex, overrideUrlRegex = WebVideoFeatures.resourceRegex,
                        javaScript = request.videoWebScript() ?: "setTimeout(() => {}, 2000); document.documentElement.outerHTML;",
                        delayTime = maxOf(request.videoWebDelay(), 2000L)).getStrResponse().body
                },
                htmlFallback = {
                    if (document == null) request.getStrResponseAwait(useWebView = true).body
                    else BackstageWebView(url = pageBase, html = document, tag = source?.getKey(), source = source,
                        headerMap = HashMap(requestHeaders), javaScript = request.videoWebScript(),
                        delayTime = request.videoWebDelay()).getStrResponse().body
                }, log = { AppLog.put("视频播放：候选 ${index + 1}/${candidates.size} $it") })
            if (resolved != null) return resolved
        }
        throw IllegalArgumentException("视频规则、后台嗅探及 HTML 回退均未解析出媒体地址")
    }
}
