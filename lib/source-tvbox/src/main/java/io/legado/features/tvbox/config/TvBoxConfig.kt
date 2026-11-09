package io.legado.features.tvbox.config

import com.google.gson.*
import java.net.URI
import java.security.MessageDigest

data class TvBoxSite(val key: String, val name: String, val type: Int, val api: String,
    val ext: String = "", val jar: String = "", val playUrl: String = "",
    val searchable: Boolean = true, val filterable: Boolean = true,
    val timeoutSeconds: Int = 30, val headers: Map<String, String> = emptyMap()) {
    val isJar get() = api.startsWith("csp_")
    val isJs get() = api.substringBefore('?').endsWith(".js", true)
}
data class TvBoxParse(val name: String, val url: String, val type: Int, val headers: Map<String, String> = emptyMap(), val flags: List<String> = emptyList(), val ext: String = "") {
    fun extendedUrl(): String {
        if (ext.isBlank() || '?' !in url) return url
        val encoded = java.util.Base64.getUrlEncoder().encodeToString(ext.toByteArray())
        return url.substringBefore('?') + "?cat_ext=$encoded&" + url.substringAfter('?')
    }
}
data class TvBoxConfig(val namespace: String, val baseUrl: String, val spider: String,
    val sites: List<TvBoxSite>, val flags: List<String>, val parses: List<TvBoxParse>, val raw: String)

object TvBoxConfigs {
    const val MAX_CONFIG = 8 * 1024 * 1024
    fun encode(configs: Collection<TvBoxConfig>): String {
        require(configs.size <= 64) { "TVBox 配置数量过多" }
        require(configs.map { it.namespace }.distinct().size == configs.size) { "TVBox 配置身份重复" }
        return Gson().toJson(mapOf("version" to 1, "configs" to configs.map {
            mapOf("namespace" to it.namespace, "base" to it.baseUrl, "json" to it.raw)
        })).also { require(it.toByteArray().size <= 32 * 1024 * 1024) { "TVBox 配置总量过大" } }
    }
    fun restore(saved: String): List<TvBoxConfig> {
        require(saved.toByteArray().size <= 32 * 1024 * 1024) { "TVBox 配置备份过大" }
        val envelope = JsonParser.parseString(saved).asJsonObject
        require(envelope["version"]?.asInt == 1) { "TVBox 配置版本未支持" }
        val entries = checkNotNull(envelope.getAsJsonArray("configs")) { "缺少 TVBox 配置列表" }
        require(entries.size() <= 64) { "TVBox 配置数量过多" }
        return entries.map { entry -> entry.asJsonObject.let { parse(it.text("json"), it.text("base"), it.text("namespace")) } }.also { all ->
            require(all.map { it.namespace }.distinct().size == all.size) { "TVBox 配置身份重复" }
        }
    }
    fun identity(base: String): String = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)
    fun parse(json: String, base: String, namespace: String = identity(base.ifBlank { json })): TvBoxConfig {
        require(json.toByteArray().size <= MAX_CONFIG) { "TVBox 配置超过 8 MiB" }
        require(namespace.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "配置命名空间无效" }
        val root = JsonParser.parseString(json).asJsonObject
        val sites = root.getAsJsonArray("sites") ?: error("TVBox 配置缺少 sites")
        require(sites.size() in 1..2000) { "TVBox 站点数量无效" }
        val mapped = sites.map { value ->
            val site = value.asJsonObject
            val key = site.text("key").trim()
            require(key.isNotBlank() && key.length <= 512) { "TVBox 站点 key 无效" }
            val api = relative(site.text("api").trim(), base)
            require(api.isNotBlank()) { "TVBox 站点 $key 缺少 api" }
            val ext = site["ext"]?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
            TvBoxSite(key, site.text("name").ifBlank { key }, site.int("type"), api,
                relative(ext, base), relative(site.text("jar"), base), relative(site.text("playUrl"), base),
                site.flag("searchable", true), site.flag("filterable", true), site.int("timeout", 30).coerceIn(1, 120), headers(site["header"]))
        }
        require(mapped.map { it.key }.distinct().size == mapped.size) { "同一配置内站点 key 重复" }
        val rawSpider = root["spider"]?.let { if (it.isJsonArray) it.asJsonArray.firstOrNull()?.asString.orEmpty() else it.asString }.orEmpty()
        val parses = root.getAsJsonArray("parses")?.map { element ->
            val p = element.asJsonObject
            val ext = p["ext"]?.takeIf { it.isJsonObject }?.asJsonObject
            TvBoxParse(p.text("name"), relative(p.text("url"), base), p.int("type"), mergeHeaders(headers(p["header"]), headers(ext?.get("header"))),
                ext?.getAsJsonArray("flag")?.map { it.asString }.orEmpty(), p["ext"]?.takeUnless { it.isJsonNull }?.toString().orEmpty())
        }.orEmpty()
        return TvBoxConfig(namespace, base, relative(rawSpider, base), mapped,
            root.getAsJsonArray("flags")?.map { it.asString }.orEmpty(), parses, json)
    }
    /** Logical file/proxy URLs stay logical until a live session owns their resources. */
    fun relative(spec: String, base: String): String {
        if (spec.startsWith("{") || spec.startsWith("[")) return spec
        return spec.split("$$$").joinToString("$$$") { segment ->
            val path = segment.substringBefore(";md5;")
            if (base.isNotBlank() && (path.startsWith("./") || path.startsWith("../") || path.startsWith('/'))) {
                URI(base).resolve(path).toString() + segment.removePrefix(path)
            } else segment
        }
    }
    fun headers(raw: JsonElement?): Map<String, String> {
        if (raw == null || raw.isJsonNull) return emptyMap()
        if (raw.isJsonPrimitive) {
            val text = raw.asString
            if (text.trim().startsWith('{')) return headers(JsonParser.parseString(text))
            return text.split('&').filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        }
        return raw.asJsonObject.entrySet().associate { it.key to it.value.asString }
    }
    fun mergeHeaders(vararg maps: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        maps.forEach { map -> map.forEach { (key, value) ->
            result.keys.firstOrNull { it.equals(key, true) }?.let(result::remove)
            result[key] = value
        } }
        return result
    }
}
internal fun JsonObject.text(key: String, fallback: String = ""): String = get(key)?.takeUnless { it.isJsonNull }?.asString ?: fallback
internal fun JsonObject.int(key: String, fallback: Int = 0): Int = text(key).toIntOrNull() ?: fallback
internal fun JsonObject.flag(key: String, fallback: Boolean): Boolean = when (text(key).lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> fallback }
