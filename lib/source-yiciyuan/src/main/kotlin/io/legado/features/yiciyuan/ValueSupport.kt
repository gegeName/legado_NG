package io.legado.features.yiciyuan

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.ParseContext
import org.htmlunit.corejs.javascript.NativeArray
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.Wrapper
import java.net.URL

internal val GSON = Gson()
internal val jsonPath: ParseContext = JsonPath.using(com.jayway.jsonpath.Configuration.defaultConfiguration())

internal fun DocumentContext.readString(path: String): String? = read<Any?>("$.$path")?.toString()
internal fun DocumentContext.readInt(path: String): Int? = runCatching { readString(path)?.toDoubleOrNull()?.toInt() }.getOrNull()
internal fun DocumentContext.readBool(path: String): Boolean? = runCatching {
    when (readString(path)?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
}.getOrNull()

internal fun String.isJson(): Boolean = runCatching {
    val value = JsonParser.parseString(this)
    value.isJsonObject || value.isJsonArray
}.getOrDefault(false)

internal inline fun <reified T> Gson.fromJsonObject(value: String): Result<T> = runCatching {
    fromJson(value, object : TypeToken<T>() {}.type)
}

internal fun absoluteUrl(base: String, relative: String): String {
    val value = relative.trim()
    if (value.startsWith("javascript", true)) return ""
    if (value.startsWith("data:", true)) return value
    return runCatching { URL(URL(base.substringBefore(',')), value).toString() }.getOrDefault(value)
}

/** Serialize runtime values without executing scripts against a global engine. */
internal fun normalizeJsValue(value: Any?): String? {
    fun unwrap(item: Any?, depth: Int): Any? {
        require(depth < 64) { "异次元 JS 返回值嵌套过深或循环引用" }
        return when (item) {
            null, is Undefined -> null
            is Wrapper -> unwrap(item.unwrap(), depth + 1)
            is NativeArray -> (0 until item.length.toInt()).map { unwrap(item.get(it, item), depth + 1) }
            is Scriptable -> item.ids.associate { id ->
                id.toString() to unwrap(when (id) {
                    is Number -> item.get(id.toInt(), item)
                    else -> item.get(id.toString(), item)
                }, depth + 1)
            }
            is Map<*, *> -> item.entries.associate { it.key.toString() to unwrap(it.value, depth + 1) }
            is List<*> -> item.map { unwrap(it, depth + 1) }
            else -> item
        }
    }
    return if (value == null || value is Undefined) null else GSON.toJson(unwrap(value, 0))
}
