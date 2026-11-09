package io.legado.features.extensioncompat

import android.app.Application
import android.content.Context
import android.webkit.WebView
import okhttp3.OkHttpClient

/** Narrow platform hooks used by the preserved third-party ABI; no NG entities cross this port. */
interface ExtensionPlatform {
    val application: Application
    val client: OkHttpClient
    fun userAgent(): String
    fun log(message: String, error: Throwable? = null)
    fun supportsWebView(context: Context): Boolean
    fun configureWebView(view: WebView, userAgent: String)
    fun isWebViewOutdated(view: WebView): Boolean
    fun syncCookies(url: String, cookie: String)
}

object ExtensionServices {
    @Volatile private var platform: ExtensionPlatform? = null

    @Synchronized fun register(host: ExtensionPlatform) {
        check(platform == null || platform === host) { "扩展平台已由另一实例初始化" }
        platform = host
    }

    fun require(): ExtensionPlatform = platform ?: error("扩展平台尚未初始化")
}

internal object CompatLog {
    fun put(message: String, error: Throwable? = null) = ExtensionServices.require().log(message, error)
}

internal const val cookieJarHeader = "CookieJar"
