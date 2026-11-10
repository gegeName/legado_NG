package io.legado.features.tvbox.sniff

import io.legado.features.api.source.VideoResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.apache.commons.text.StringEscapeUtils
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonParser

/** Port of legado AnalyzeUrl.getMediaItemAwait; the host supplies its existing rule/WebView runtime. */
class LegadoVideoResolver(
    private val probe: suspend () -> WebVideoProbe?,
    private val sniff: suspend () -> String?,
    private val htmlFallback: suspend () -> String?,
    private val log: (String) -> Unit = {},
    private val sniffTimeoutMs: Long = SNIFF_TIMEOUT_MS
) {
    suspend fun resolve(input: String, headers: Map<String, String>): VideoResource? {
        val target = input.trim()
        if (target.isBlank()) return null
        fun result(url: String) = VideoResource(url, "默认", headers.toMap(), preferred = true)
        // NG also accepts local extension media and DASH files through this entry.
        if (isValidVideoUrl(target) || Regex("^(file|content|proxy|rtsp|rtsps|rtmp|rtmps):", RegexOption.IGNORE_CASE).containsMatchIn(target)) {
            log("直连视频地址有效")
            return result(target)
        }
        extractVideoUrlFromText(target)?.takeIf(::isValidVideoUrl)?.let {
            log("从正文或内嵌播放页提取媒体")
            return result(it)
        }
        val response = attempt { probe() }
        if (response != null && response.successful) {
            if (response.contentType.contains("video/", true) || response.contentType.contains("mpegurl", true) ||
                response.contentType.contains("dash+xml", true)) return result(response.url)
            val direct = when {
                isValidVideoUrl(response.url) -> response.url
                isValidVideoUrl(response.text) -> response.text
                else -> extractVideoUrlFromText(response.text)
            }
            if (!direct.isNullOrBlank() && isValidVideoUrl(direct)) return result(direct)
            if (response.text.contains("#EXTM3U", true)) return result(response.url)
        }
        log("后台 WebView 嗅探")
        // Only our own sniff deadline falls through; parent cancellation must still propagate.
        val sniffed = withTimeoutOrNull(sniffTimeoutMs) { attempt { sniff() } }?.trim().orEmpty()
        val extracted = extractVideoUrlFromText(sniffed)
        when {
            sniffed.isNotBlank() && isValidVideoUrl(sniffed) -> return result(sniffed)
            !extracted.isNullOrBlank() && isValidVideoUrl(extracted) -> return result(extracted)
        }
        log("嗅探未命中，执行原规则 HTML 回退")
        val html = attempt { htmlFallback() }.orEmpty()
        val raw = htmlEmbedVideoRegex.find(html)?.groupValues?.get(1)
            ?: generalHtmlVideoRegex.find(html)?.groupValues?.get(1)
        val matched = raw?.let { runCatching { StringEscapeUtils.unescapeJava(it) }.getOrDefault(it) }
        return matched?.takeIf(::isValidVideoUrl)?.let(::result)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }

    companion object {
        const val SNIFF_TIMEOUT_MS = 12_000L
        const val RESOURCE_REGEX = ".*(?:(?:\\.|\\/)(?:m3u8|mp4|flv|m4s|webm|mpd)(?:\\?.*)?$|(?:\\/m3u8\\/|\\/hls\\/|\\/playlist\\/|index\\.m3u8)).*"
        private val htmlEmbedVideoRegex = Regex(
            """(?:['"](?:url|video_url|link|file|src)['"]\s*[:=]\s*['"])(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|flv|m4s|webm)(?:\?[^\s"'<>\\]*)?)(?:['"])""", RegexOption.IGNORE_CASE)
        private val generalHtmlVideoRegex = Regex(
            """(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|flv|m4s|webm)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)

        fun isValidVideoUrl(url: String): Boolean {
            val lower = url.lowercase().trim()
            if (lower.isBlank()) return false
            if (lower.startsWith("data:application/vnd.apple.mpegurl") || lower.startsWith("data:application/x-mpegurl") ||
                lower.startsWith("data:video/")) return true
            if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtmp://") ||
                    lower.startsWith("rtmps://") || lower.startsWith("rtsp://"))) return false
            if (lower.contains("/player/") || lower.contains("player.php") || lower.contains("player.html") ||
                lower.contains("?url=http") || lower.contains("&url=http") || lower.contains("/jx/?")) return false
            val path = lower.substringBefore('?')
            if (listOf(".m3u8", ".mp4", ".flv", ".m4s", ".ts", ".webm").any(path::endsWith)) return true
            return lower.contains(".m3u8") || lower.contains(".mp4?") || lower.contains("/hls/") || lower.contains("/m3u8/")
        }

        private fun extractGzipM3u8OrUrlFromHtml(html: String): String? {
            val encoded = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""").find(html)?.groupValues?.get(1)
            if (!encoded.isNullOrBlank()) {
                val playlist = runCatching {
                    val bytes = requireNotNull(encoded.decodeBase64()).toByteArray()
                    GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()
                if (!playlist.isNullOrBlank() && playlist.contains("#EXTM3U")) {
                    return "data:application/vnd.apple.mpegurl;base64," + playlist.toByteArray().toByteString().base64()
                }
            }
            return null
        }

        fun extractVideoUrlFromText(text: String): String? {
            if (text.isBlank()) return null
            var rawText = text.trim()
            if (rawText.startsWith("data:text/html", true) && rawText.contains("base64,")) {
                val html = runCatching { rawText.substringAfter("base64,").trim().decodeBase64()?.utf8() }.getOrNull()
                if (!html.isNullOrBlank()) {
                    extractGzipM3u8OrUrlFromHtml(html)?.let { return it }
                    rawText = html
                }
            }
            val raw = generalHtmlVideoRegex.find(rawText)?.groupValues?.get(1)
                ?: htmlEmbedVideoRegex.find(rawText)?.groupValues?.get(1)
            val matched = raw?.let { runCatching { StringEscapeUtils.unescapeJava(it) }.getOrDefault(it).trim() }
            if (!matched.isNullOrBlank()) return matched
            val any = Regex("""(https?://[^\s"'<>\u4e00-\u9fa5]+)""").find(rawText)?.groupValues?.get(1)
            return any?.trim()?.takeIf(::isValidVideoUrl)
        }

        fun mimeType(url: String): String? {
            val lower = url.substringBefore('#').lowercase()
            val path = lower.substringBefore('?')
            return when {
                lower.startsWith("data:application/vnd.apple.mpegurl") || lower.startsWith("data:application/x-mpegurl") ||
                    path.endsWith(".m3u8") || lower.contains("m3u8") -> "application/x-mpegURL"
                path.endsWith(".mpd") || lower.contains("mpd") -> "application/dash+xml"
                path.endsWith(".ism") || path.endsWith(".ism/manifest") || lower.contains("/manifest") -> "application/vnd.ms-sstr+xml"
                path.endsWith(".flv") || lower.contains(".flv") || lower.contains("http-flv") -> "video/x-flv"
                path.endsWith(".ts") || lower.contains("/ts/") -> "video/mp2t"
                path.endsWith(".mp4") || lower.contains("mp4") || path.endsWith(".mov") -> "video/mp4"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".avi") -> "video/x-msvideo"
                path.endsWith(".3gp") || path.endsWith(".3g2") -> "video/3gpp"
                else -> null
            }
        }

        fun streamResource(url: String): Boolean {
            val lower = url.lowercase()
            return listOf(".m3u8", ".flv", ".ts", ".key", ".ism", ".mpd", "http-flv", "rtmp://", "rtsp://",
                "/hls/", "/live/", "live.").any(lower::contains)
        }

        fun candidates(raw: String): List<String> {
            val lines = raw.lines().map(String::trim).filter(String::isNotEmpty)
            val candidates = if (lines.any { it.contains("::") }) lines else listOf(raw.trim())
            return candidates.map { candidate ->
                val separator = candidate.indexOf("::")
                if (separator >= 0) candidate.substring(separator + 2).trim() else candidate
            }
        }

        fun legacySource(raw: String): VideoResource {
            val separator = raw.indexOf("🚧")
            if (separator < 0) return VideoResource(raw, "默认")
            val headers = runCatching {
                Gson().fromJson<Map<String, String>>(raw.substring(separator + "🚧".length),
                    object : TypeToken<Map<String, String>>() {}.type)
            }.getOrNull().orEmpty()
            return VideoResource(raw.substring(0, separator), "默认", headers)
        }

        /** Reference VideoSource JSON returned by legacy extension rules. */
        fun pluginSource(raw: String): VideoResource? = runCatching {
            if (!raw.trimStart().startsWith("{")) return@runCatching null
            val source = JsonParser.parseString(raw).asJsonObject
            val resolutions = source.getAsJsonArray("resolutions") ?: return@runCatching null
            val index = source.get("defaultIndex")?.asInt ?: 0
            if (index !in 0 until resolutions.size()) return@runCatching null
            val selected = resolutions[index].asJsonObject
            fun headers(value: com.google.gson.JsonElement?): Map<String, String> =
                value?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            VideoResource(selected.get("url")?.asString.orEmpty(), selected.get("name")?.asString.orEmpty(),
                headers(source.get("headers")) + headers(selected.get("headers")), preferred = true)
        }.getOrNull()
    }
}
