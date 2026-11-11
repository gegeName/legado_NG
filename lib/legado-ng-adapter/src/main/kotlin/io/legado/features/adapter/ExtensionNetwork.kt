package io.legado.features.adapter

import eu.kanade.tachiyomi.network.interceptor.ExtensionCookieInterceptor
import okhttp3.Request
import okhttp3.Response

/** Optional cookie hooks for host transports that bypass OkHttp's built-in cookie bridge. */
object ExtensionNetwork {
    fun prepareTransportRequest(request: Request): Request = ExtensionCookieInterceptor.prepareTransportRequest(request)
    fun saveTransportResponse(response: Response) = ExtensionCookieInterceptor.saveTransportResponse(response)
}
