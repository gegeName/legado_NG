package io.legado.features.adapter

import android.content.Context
import android.webkit.WebView
import io.legado.features.api.source.VideoResource
import io.legado.features.tvbox.sniff.WebVideoProbe
import io.legado.features.tvbox.sniff.WebVideoResolver
import io.legado.features.tvbox.sniff.WebVideoUrls
import io.legado.features.tvbox.sniff.WebViewSniffer
import io.legado.features.tvbox.sniff.LegadoVideoResolver
import io.legado.features.tvbox.sniff.LegadoM3u8AdFilter

object WebVideoFeatures {
    data class Probe(val url: String, val type: String, val text: String,
        val headers: Map<String, String>, val successful: Boolean)

    fun isDash(text: String) = WebVideoUrls.dash(text)
    fun isNetwork(url: String) = WebVideoUrls.network(url)
    fun playbackInput(captured: String?, fallback: String) = WebVideoUrls.playbackInput(captured, fallback)
    val resourceRegex get() = LegadoVideoResolver.RESOURCE_REGEX
    fun mimeType(url: String) = LegadoVideoResolver.mimeType(url)
    fun streamResource(url: String) = LegadoVideoResolver.streamResource(url)
    fun inlineHtml(input: String) = WebVideoUrls.inlineHtml(input)
    fun candidates(input: String) = LegadoVideoResolver.candidates(input)
    fun legacySource(input: String) = LegadoVideoResolver.legacySource(input)
    fun pluginSource(input: String) = LegadoVideoResolver.pluginSource(input)
    fun filterContent(input: String, log: (String) -> Unit) = LegadoM3u8AdFilter.filter(input, log)

    suspend fun resolveLegado(input: String, headers: Map<String, String>, probe: suspend () -> Probe?,
        sniff: suspend () -> String?, htmlFallback: suspend () -> String?, log: (String) -> Unit): VideoResource? =
        LegadoVideoResolver({ probe()?.let { WebVideoProbe(it.url, it.type, it.text, it.headers, it.successful) } },
            sniff, htmlFallback, log).resolve(input, headers)

    suspend fun resolve(context: Context, url: String, headers: Map<String, String>, baseUrl: String,
        configure: (WebView) -> Unit, probe: suspend () -> Probe): VideoResource =
        WebVideoResolver({ probe().let { WebVideoProbe(it.url, it.type, it.text, it.headers, it.successful) } },
            WebViewSniffer(context.applicationContext, configure, WebVideoUrls.inlineHtml(url)))
            .resolve(url, headers, baseUrl)
}
