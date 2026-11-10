package io.legado.features.tvbox.sniff

import io.legado.features.api.source.VideoResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import okio.ByteString.Companion.decodeBase64
import kotlin.time.Duration.Companion.milliseconds

/** HTTP inspection is supplied by the host so source cookies, proxy and request rules survive. */
data class WebVideoProbe(val url: String, val contentType: String, val text: String,
    val headers: Map<String, String> = emptyMap(), val successful: Boolean = true)

object WebVideoUrls {
    private const val MAX_INLINE_BYTES = 2 * 1024 * 1024
    private val extensions = Regex("\\.(m3u8|mp4|mkv|flv|mpd|webm|mov|m4v)$", RegexOption.IGNORE_CASE)
    private val embedded = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val source = Regex("""<(?:video|source|iframe)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun network(url: String): Boolean = runCatching {
        URI(url).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() }
    }.getOrDefault(false)

    // Inspect only the path: /player?url=https://cdn/a.m3u8 is a web page, not a playlist.
    fun media(url: String): Boolean = network(url) && runCatching {
        val path = URI(url).path.orEmpty()
        extensions.containsMatchIn(path) || path.endsWith(".ism/manifest", true)
    }.getOrDefault(false)

    fun direct(url: String): Boolean = media(url) ||
        Regex("^(?:file|content|rtsp|rtsps|rtmp|rtmps|proxy):", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
        Regex("^data:(?:video/|application/(?:vnd\\.apple\\.mpegurl|x-mpegurl|dash\\+xml))", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun mediaType(type: String): Boolean = type.startsWith("video/", true) ||
        type.contains("mpegurl", true) || type.contains("dash+xml", true) || type.contains("vnd.ms-sstr", true)

    fun dash(text: String): Boolean = Regex("""<(?:\w+:)?MPD(?:\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Only HTML data URIs are documents; custom data rule payloads stay opaque. */
    fun inlineHtml(input: String): String? {
        val text = input.trim()
        if (text.startsWith("<")) return text
        if (!text.startsWith("data:text/html", true) || text.length > MAX_INLINE_BYTES * 2) return null
        val separator = text.indexOf(',')
        if (separator < 0) return null
        val metadata = text.substring(0, separator)
        if (!metadata.substring(5).substringBefore(';').equals("text/html", true)) return null
        return runCatching {
            val body = text.substring(separator + 1)
            if (metadata.endsWith(";base64", true)) body.decodeBase64()?.utf8()
            else URLDecoder.decode(body.replace("+", "%2B"), "UTF-8")
        }.getOrNull()
    }

    fun playbackInput(captured: String?, fallback: String): String = captured?.takeIf {
        network(it) || direct(it) || inlineHtml(it) != null
    } ?: fallback

    private fun inlinePlaylist(html: String): String? {
        val encoded = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""")
            .findAll(html).map { it.groupValues[1] }
        for (value in encoded) {
            val playlist = runCatching {
                val bytes = value.decodeBase64()?.toByteArray() ?: return@runCatching null
                GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_INLINE_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().takeIf { it.toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U") }
                }
            }.getOrNull()
            if (playlist != null) return "data:application/vnd.apple.mpegurl;base64," +
                okio.ByteString.of(*playlist).base64()
        }
        return null
    }

    fun extract(text: String, base: String): String? {
        val document = inlineHtml(text) ?: text
        inlinePlaylist(document)?.let { return it }
        val decoded = document.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val sources = source.findAll(decoded).mapNotNull {
            runCatching { URI(base).resolve(it.groupValues[1]).toString() }.getOrNull()
        }.toList()
        return sources.firstOrNull(::media)
            ?: embedded.findAll(decoded).map { it.value }.firstOrNull(::media)
            ?: sources.firstOrNull(::network)
    }

    fun headers(vararg maps: Map<String, String>): Map<String, String> = linkedMapOf<String, String>().apply {
        maps.forEach { map -> map.forEach { (name, value) ->
            if (!name.equals("Range", true) && !name.equals("Host", true) &&
                !name.equals("Content-Length", true) && !name.equals("Connection", true)) {
                keys.firstOrNull { it.equals(name, true) }?.let(::remove)
                put(name, value)
            }
        } }
    }

    fun forTarget(origin: String, target: String, headers: Map<String, String>): Map<String, String> {
        val sameHost = runCatching { URI(origin).host.equals(URI(target).host, true) }.getOrDefault(false)
        return if (sameHost) headers else headers.filterKeys {
            !it.equals("Cookie", true) && !it.equals("Authorization", true)
        }
    }
}

class WebVideoResolver(private val probe: suspend () -> WebVideoProbe?, private val sniffer: SniffPort) {
    suspend fun resolve(input: String, headers: Map<String, String>, baseUrl: String = input,
        timeoutMs: Long = 30_000): VideoResource = withTimeout(timeoutMs.milliseconds) {
        val target = input.trim()
        val initial = WebVideoUrls.headers(headers)
        if (WebVideoUrls.direct(target)) return@withTimeout VideoResource(target, "默认", initial, preferred = true)
        if (!WebVideoUrls.network(target)) {
            val extracted = WebVideoUrls.extract(target, baseUrl)
            if (extracted != null && WebVideoUrls.direct(extracted)) {
                return@withTimeout VideoResource(extracted, "网页视频",
                    WebVideoUrls.headers(WebVideoUrls.forTarget(baseUrl, extracted, initial),
                        if (WebVideoUrls.network(baseUrl)) mapOf("Referer" to baseUrl) else emptyMap()), preferred = true)
            }
            val page = if (WebVideoUrls.inlineHtml(target) != null) baseUrl else extracted ?: baseUrl
            require(WebVideoUrls.network(page)) { "未找到有效的视频播放网页" }
            return@withTimeout sniffer.sniff(page, initial, timeoutMs)
        }
        val response = try { probe() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        val playbackHeaders = WebVideoUrls.headers(WebVideoUrls.forTarget(target, response?.url ?: target, initial),
            response?.headers.orEmpty())
        if (response?.successful == true) {
            if (WebVideoUrls.mediaType(response.contentType) ||
                (!response.contentType.contains("html", true) && WebVideoUrls.media(response.url)) ||
                response.text.trimStart().startsWith("#EXTM3U")) {
                return@withTimeout VideoResource(response.url, "默认", playbackHeaders, preferred = true)
            }
            WebVideoUrls.extract(response.text, response.url)?.let { extracted ->
                if (WebVideoUrls.direct(extracted)) return@withTimeout VideoResource(extracted, "网页视频",
                    WebVideoUrls.headers(WebVideoUrls.forTarget(response.url, extracted, playbackHeaders),
                        mapOf("Referer" to response.url)), preferred = true)
            }
        }
        sniffer.sniff(response?.url?.takeIf(WebVideoUrls::network) ?: target, playbackHeaders, timeoutMs)
    }
}
