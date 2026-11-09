

package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import io.legado.features.extensioncompat.CompatLog
import io.legado.features.extensioncompat.ExtensionServices
import okhttp3.Headers
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : ChallengeInterceptorBase() {

    
    private val initWebView by lazy {
        val manufacturer = Build.MANUFACTURER
        if (manufacturer.equals("Xiaomi", true) ||
            (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && manufacturer.equals("Samsung", true))
        ) {
            return@lazy
        }

        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {

        }
    }

    override fun isEngineAvailable(): Boolean = ExtensionServices.require().supportsWebView(context)

    override fun onEngineUnavailable() {
        CompatLog.put("WebView 不可用, 无法通过 Cloudflare 挑战")
    }

    override fun prepareEngine() {
        initWebView
    }

    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers

            .filter { (name, value) ->
                isRequestHeaderSafe(name, value)
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    fun CountDownLatch.awaitChallengeWindow() {
        await(CHALLENGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            ExtensionServices.require().configureWebView(this, request.header("User-Agent") ?: defaultUserAgentProvider())
        }
    }
}

private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)
    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}
private val unsafeHeaderNames = listOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding", "set-cookie",
)

