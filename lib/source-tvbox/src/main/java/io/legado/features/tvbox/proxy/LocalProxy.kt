package io.legado.features.tvbox.proxy

import fi.iki.elonen.NanoHTTPD
import io.legado.features.tvbox.provider.SpiderPort
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Every URL belongs to a process session and a single namespace/site. */
class LocalProxy(private val files: File) : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
    private data class Route(val namespace: String, val site: String, val action: (Map<String, String>) -> Array<Any?>?)
    private val token = UUID.randomUUID().toString()
    private val routes = ConcurrentHashMap<String, Route>()
    init { start(SOCKET_READ_TIMEOUT, true); com.github.catvod.Proxy.set(listeningPort) }
    fun register(namespace: String, site: String, action: (Map<String, String>) -> Array<Any?>?): String {
        val id = UUID.randomUUID().toString()
        routes[id] = Route(namespace, site, action)
        return "http://127.0.0.1:$listeningPort/$token/$id/proxy"
    }
    fun invalidate(namespace: String) { routes.entries.removeAll { it.value.namespace == namespace } }
    fun logical(url: String, endpoint: String): String = when {
        url.startsWith("proxy://") -> endpoint + "?" + url.removePrefix("proxy://")
        url.startsWith("file://") -> endpoint.substringBeforeLast('/') + "/file?path=" + java.net.URLEncoder.encode(url.removePrefix("file://"), "UTF-8")
        else -> url
    }
    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 3 || parts[0] != token) return error(Response.Status.NOT_FOUND, "会话不存在")
        val route = routes[parts[1]] ?: return error(Response.Status.GONE, "会话已关闭")
        if (session.method !in listOf(Method.GET, Method.HEAD)) return error(Response.Status.METHOD_NOT_ALLOWED, "仅支持读取")
        return try {
            if (parts[2] == "file") {
                val root = File(files, route.namespace).canonicalFile
                val path = File(session.parameters["path"]?.firstOrNull().orEmpty())
                val file = (if (path.isAbsolute) path else File(root, path.path)).canonicalFile
                if (!file.toPath().startsWith(root.toPath()) || !file.isFile) return error(Response.Status.NOT_FOUND, "文件不在站点目录")
                return fileResponse(file, session.headers["range"], session.method == Method.HEAD)
            }
            if (parts[2] != "proxy") return error(Response.Status.NOT_FOUND, "路径不存在")
            val params = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }.toMutableMap()
            session.headers["range"]?.let { params["range"] = it; params["Range"] = it }
            val result = route.action(params) ?: return error(Response.Status.NOT_FOUND, "Spider 未处理代理")
            val status = Response.Status.lookup((result.getOrNull(0) as? Number)?.toInt() ?: 200) ?: Response.Status.OK
            val mime = result.getOrNull(1)?.toString() ?: "application/octet-stream"
            val body = result.getOrNull(2)
            val response = when (body) {
                is InputStream -> newChunkedResponse(status, mime, body)
                is ByteArray -> newFixedLengthResponse(status, mime, body.inputStream(), body.size.toLong())
                else -> newFixedLengthResponse(status, mime, body?.toString().orEmpty())
            }
            (result.getOrNull(3) as? Map<*, *>)?.forEach { (k, v) -> if (k != null && v != null && k.toString().lowercase() !in setOf("connection", "transfer-encoding")) response.addHeader(k.toString(), v.toString()) }
            if (session.method == Method.HEAD) {
                response.data?.close()
                response.data = byteArrayOf().inputStream()
                response.setChunkedTransfer(false)
            }
            response
        } catch (_: Exception) { error(Response.Status.INTERNAL_ERROR, "TVBox 代理失败") }
    }
    private fun fileResponse(file: File, range: String?, head: Boolean): Response {
        val length = file.length()
        val requested = range?.takeIf { it.startsWith("bytes=") && ',' !in it }?.removePrefix("bytes=")
        if (range != null && (requested == null || !requested.matches(Regex("[0-9]*-[0-9]*")) || requested == "-")) {
            return error(Response.Status.RANGE_NOT_SATISFIABLE, "无效 Range").apply { addHeader("Content-Range", "bytes */$length") }
        }
        val first = requested?.substringBefore('-').orEmpty()
        val last = requested?.substringAfter('-', "").orEmpty()
        val suffix = if (requested != null && first.isBlank()) last.toLongOrNull() else null
        val start = if (suffix != null) (length - suffix).coerceAtLeast(0) else first.toLongOrNull() ?: 0L
        val end = if (suffix != null) length - 1 else last.toLongOrNull()?.coerceAtMost(length - 1) ?: length - 1
        if (range != null && (requested == null || start !in 0 until length || end < start || suffix != null && suffix <= 0)) {
            return error(Response.Status.RANGE_NOT_SATISFIABLE, "无效 Range").apply { addHeader("Content-Range", "bytes */$length") }
        }
        // NanoHTTPD 2.3.1 still reads a fixed body for HEAD: keep the real length but use EOF.
        val input = if (head) byteArrayOf().inputStream() else file.inputStream().apply {
            var remaining = start
            while (remaining > 0) { val skipped = skip(remaining); check(skipped > 0); remaining -= skipped }
        }
        return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
            "application/octet-stream", input, (end - start + 1).coerceAtLeast(0)).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/$length")
        }
    }
    private fun error(status: Response.Status, text: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", text)
    override fun close() { routes.clear(); stop(); com.github.catvod.Proxy.set(-1) }
}
