package io.legado.features.api.source

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Durable references keep the original plugin URL; process recreation needs no in-memory token map. */
object PluginUris {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    fun source(key: SourceKey): String = "feature-source://${key.provider}/${encode(key.nativeId)}" + if (key.namespace.isBlank()) "" else "?ns=${encode(key.namespace)}"
    fun source(value: String, kind: ContentKind): SourceKey? {
        if (value.startsWith("tachiyomi://")) {
            val id = value.removePrefix("tachiyomi://").substringBefore('/').takeIf { it.toLongOrNull() != null } ?: return null
            return SourceKey(if (kind == ContentKind.VIDEO) "aniyomi" else "mihon", kind, id)
        }
        if (value.startsWith("tvbox://")) {
            require(kind == ContentKind.VIDEO) { "TVBox 只支持视频" }
            val id = value.removePrefix("tvbox://").substringBefore('/').takeIf { it.isNotBlank() } ?: return null
            return SourceKey("tvbox", kind, decode(id))
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "feature-source" || uri.host !in setOf("mihon", "aniyomi", "tvbox")) return null
        val expected = if (uri.host == "mihon") ContentKind.MANGA else ContentKind.VIDEO
        require(kind == expected) { "扩展来源与内容类型不匹配" }
        val namespace = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("ns=") }?.substringAfter('=')?.let(::decode).orEmpty()
        return SourceKey(uri.host, kind, decode(uri.rawPath.removePrefix("/")), namespace)
    }
    private fun bookPrefix(key: SourceKey) = "feature-book://${key.provider}/" + (if (key.namespace.isBlank()) "" else "@${encode(key.namespace)}/") + "${encode(key.nativeId)}/"
    fun book(key: SourceKey, id: String) = bookPrefix(key) + encode(id)
    fun original(value: String, key: SourceKey): String {
        val prefix = bookPrefix(key)
        if (value.startsWith(prefix)) return decode(value.removePrefix(prefix))
        val legacy = "tachiyomi://${key.nativeId}/"
        if (value.startsWith(legacy)) return decode(value.removePrefix(legacy))
        val legacyTv = "tvbox://${key.nativeId}/"
        if (key.provider == "tvbox" && value.startsWith(legacyTv)) return decode(value.removePrefix(legacyTv))
        require(!value.startsWith("feature-book:") && !value.startsWith("tachiyomi:") && !value.startsWith("tvbox:")) { "作品地址与扩展来源不匹配" }
        return value
    }
    fun image(ref: ImageReference): String = "feature-image://${ref.source.provider}/${encode(ref.source.nativeId)}?i=${ref.index}&p=${encode(ref.pageUrl)}&u=${encode(ref.imageUrl.orEmpty())}"
    fun image(value: String): ImageReference? {
        val normalizedValue = value.replace("&amp;", "&")
        if (normalizedValue.startsWith("tachiyomi-img://")) {
            val parts = normalizedValue.removePrefix("tachiyomi-img://").split('/', limit = 2)
            if (parts.size != 2 || parts[0].toLongOrNull() == null) return null
            return ImageReference(SourceKey("mihon", ContentKind.MANGA, parts[0]), 0, "", decode(parts[1]))
        }
        val uri = runCatching { URI(normalizedValue) }.getOrNull() ?: return null
        if (uri.scheme != "feature-image" || uri.host != "mihon") return null
        val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to decode(it.substringAfter('=', "")) }
        return ImageReference(SourceKey("mihon", ContentKind.MANGA, decode(uri.rawPath.removePrefix("/"))), query["i"]?.toIntOrNull() ?: 0, query["p"].orEmpty(), query["u"]?.takeIf { it.isNotEmpty() })
    }
}
