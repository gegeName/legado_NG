package eu.kanade.tachiyomi.network.interceptor

import io.legado.features.extensioncompat.cookieJarHeader
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/** Host transports may return before OkHttp's BridgeInterceptor runs. */
class ExtensionCookieInterceptor(private val cookieJar: CookieJar) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tagged = request.newBuilder()
            // This marker belongs to NG's separate cookie store, which may still contain old values.
            .removeHeader(cookieJarHeader)
            .tag(CookieContext::class.java, CookieContext(cookieJar, request.url, request.headers("Cookie")))
            .build()
        // Normal OkHttp keeps its native cookie/redirect handling. Alternate transports use
        // the context at each actual send, including redirects, rather than copying headers.
        return chain.proceed(tagged)
    }

    private class CookieContext(val jar: CookieJar, val origin: HttpUrl, val originalCookies: List<String>) {
        @Volatile var transportRequest: Request? = null
    }

    companion object {
        fun networkInterceptor(): Interceptor = Interceptor { chain ->
            chain.proceed(chain.request()).also { response ->
                response.request.tag(CookieContext::class.java)?.transportRequest = response.request
            }
        }

        fun transportRequest(request: Request): Request? =
            request.tag(CookieContext::class.java)?.transportRequest

        fun prepareTransportRequest(request: Request): Request {
            val context = request.tag(CookieContext::class.java) ?: return request
            val cookies = linkedMapOf<String, String>()
            if (request.url.scheme == context.origin.scheme && request.url.host == context.origin.host && request.url.port == context.origin.port) {
                context.originalCookies.forEach { header ->
                    header.split(';').forEach { part ->
                        val index = part.indexOf('=')
                        if (index > 0) cookies[part.take(index).trim()] = part.substring(index + 1).trim()
                    }
                }
            }
            // The current WebView jar wins over headers captured before verification.
            context.jar.loadForRequest(request.url).forEach { cookies[it.name] = it.value }
            return request.newBuilder().removeHeader("Cookie")
                .apply {
                    if (cookies.isNotEmpty()) header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }.build().also { context.transportRequest = it }
        }

        fun saveTransportResponse(response: Response) {
            val context = response.request.tag(CookieContext::class.java) ?: return
            val received = Cookie.parseAll(response.request.url, response.headers)
            if (received.isNotEmpty()) context.jar.saveFromResponse(response.request.url, received)
        }
    }
}
