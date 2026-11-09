package com.github.catvod.net

import io.legado.features.tvbox.TvBoxHttp
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers.Companion.toHeaders
import java.util.concurrent.TimeUnit

/** ABI facade uses host TLS/cookies. It deliberately has no trust-all TLS fallback. */
object OkHttp {
    @Volatile var transport = TvBoxHttp(OkHttpClient())
    @JvmStatic fun get() = this
    @JvmStatic fun client() = transport.client
    @JvmStatic fun player() = client()
    @JvmStatic fun client(timeout: Long) = client().newBuilder().callTimeout(timeout.coerceIn(1000, 120000), TimeUnit.MILLISECONDS).build()
    @JvmStatic fun dns(): Dns = client().dns
    @JvmStatic fun noRedirect() = client().newBuilder().followRedirects(false).followSslRedirects(false).build()
    @JvmStatic fun noRedirect(timeout: Long) = noRedirect().newBuilder().callTimeout(timeout.coerceIn(1000, 120000), TimeUnit.MILLISECONDS).build()
    @JvmStatic fun string(url: String) = string(url, emptyMap())
    @JvmStatic fun string(url: String, header: Map<String, String>?) = transport.text(url, header.orEmpty())
    @JvmStatic fun string(url: String, params: Map<String, String>?, header: Map<String, String>?): String = string(buildUrl(url, params), header)
    @JvmStatic fun buildUrl(url: String, params: Map<String, String>?): String = url.toHttpUrl().newBuilder()
        .apply { params?.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    @JvmStatic fun newCall(url: String) = newCall(url, emptyMap())
    @JvmStatic fun newCall(url: String, header: Map<String, String>?) = client().newCall(Request.Builder().url(url).headers(header.orEmpty().toHeaders()).build())
    @JvmStatic fun newCall(url: String, params: Map<String, String>?, header: Map<String, String>?) = newCall(buildUrl(url, params), header)
    @JvmStatic fun post(url: String, params: Map<String, String>?) = post(url, params, emptyMap())
    @JvmStatic fun post(url: String, params: Map<String, String>?, header: Map<String, String>?): String {
        val body = FormBody.Builder().apply { params?.forEach { (k, v) -> add(k, v) } }.build()
        return transport.execute(Request.Builder().url(url).headers(header.orEmpty().toHeaders()).post(body).build()) { response, bytes ->
            check(response.isSuccessful) { "TVBox HTTP ${response.code}" }; bytes.toString(Charsets.UTF_8)
        }
    }
}
