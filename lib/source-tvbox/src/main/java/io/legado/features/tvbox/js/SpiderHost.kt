package io.legado.features.tvbox.js

import com.google.gson.*
import io.legado.features.api.host.ConfigPort
import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.config.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.net.URI
import java.security.MessageDigest

internal class SpiderHost(private val site: TvBoxSite, private val namespace: String,
    private val http: TvBoxHttp, private val config: ConfigPort,
    private val asset: (String) -> String, private val proxy: () -> String) {
    private val gson = Gson()
    fun call(method: String, arguments: String): String {
        val args = JsonParser.parseString(arguments).asJsonArray.map { it.asString }
        fun arg(i: Int) = args.getOrNull(i).orEmpty()
        return when (method) {
            "fetch" -> {
                val name = arg(0)
                val url = if (name.startsWith("assets://")) name else URI(arg(1).ifBlank { site.api }).resolve(name).toString()
                if (url.startsWith("assets://")) asset(url.removePrefix("assets://")) else {
                    require(url.startsWith("http://") || url.startsWith("https://")) { "JS 模块仅支持 HTTP(S)/assets" }
                    http.text(url, site.headers, 4 * 1024 * 1024)
                }
            }
            "req" -> {
                val options = JsonParser.parseString(arg(1).ifBlank { "{}" }).asJsonObject
                val headers = site.headers + TvBoxConfigs.headers(options["headers"])
                val methodName = options.text("method", "GET").uppercase()
                val request = Request.Builder().url(arg(0)).apply { headers.forEach { (k, v) -> header(k, v) } }
                if (methodName in setOf("POST", "PUT", "PATCH")) {
                    val raw = options["body"] ?: options["data"]
                    val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                    val body = if (raw?.isJsonObject == true && type?.contains("json") != true) okhttp3.FormBody.Builder().apply {
                        raw.asJsonObject.entrySet().forEach { (k, v) -> add(k, if (v.isJsonPrimitive) v.asString else v.toString()) }
                    }.build() else (if (raw?.isJsonPrimitive == true) raw.asString else raw?.toString().orEmpty()).toRequestBody(type?.toMediaTypeOrNull())
                    request.method(methodName, body)
                }
                else if (methodName == "HEAD" || methodName == "HEADER") request.head()
                else require(methodName == "GET") { "JS HTTP 方法未支持: $methodName" }
                val transport = TvBoxHttp(http.client.newBuilder()
                    .followRedirects(options.int("redirect", 1) != 0).followSslRedirects(options.int("redirect", 1) != 0)
                    .callTimeout(options.int("timeout", 30000).coerceIn(1000, 120000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS).build())
                transport.execute(request.build()) { response, bytes ->
                    val buffer = options.int("buffer")
                    require(buffer in 0..2) { "JS buffer 模式未支持" }
                    val content = if (buffer == 0) bytes.toString(response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        else java.util.Base64.getEncoder().encodeToString(bytes)
                    gson.toJson(mapOf("code" to response.code, "headers" to response.headers.toMultimap(), "content" to content))
                }
            }
            "joinUrl" -> URI(arg(0)).resolve(arg(1)).toString()
            "md5" -> MessageDigest.getInstance("MD5").digest(arg(0).toByteArray()).joinToString("") { "%02x".format(it) }
            "getProxy" -> proxy()
            "getPort" -> URI(proxy()).port.toString()
            "js2Proxy" -> proxy() + "?do=js&url=" + java.net.URLEncoder.encode(arg(3), "UTF-8") + "&headers=" + java.net.URLEncoder.encode(arg(4), "UTF-8")
            "aes" -> com.github.catvod.utils.Crypto.aes(arg(0), arg(1) == "1", arg(2), arg(3) == "1", arg(4), arg(5), arg(6) == "1")
            "des" -> com.github.catvod.utils.Crypto.des(arg(0), arg(1) == "1", arg(2), arg(3) == "1", arg(4), arg(5), arg(6) == "1")
            "rsa" -> com.github.catvod.utils.Crypto.rsa(arg(0), arg(1) == "1", arg(2) == "1", arg(3), arg(4) == "1", arg(5), arg(6) == "1")
            "localGet" -> config.get("tvbox-js:$namespace:${site.key}", "${arg(0)}:${arg(1)}") ?: arg(2)
            "localSet" -> { config.put("tvbox-js:$namespace:${site.key}", "${arg(0)}:${arg(1)}", arg(2)); "" }
            "localDelete" -> { config.put("tvbox-js:$namespace:${site.key}", "${arg(0)}:${arg(1)}", null); "" }
            "log" -> ""
            else -> throw UnsupportedOperationException("JS Spider 宿主 API 未支持: $method")
        }
    }
}
