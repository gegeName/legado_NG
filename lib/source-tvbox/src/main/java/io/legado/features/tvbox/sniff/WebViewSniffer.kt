package io.legado.features.tvbox.sniff

import android.content.Context
import android.webkit.*
import io.legado.features.api.source.VideoResource
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a fresh WebView, destroyed on success, error, timeout and cancellation. */
class WebViewSniffer(private val context: Context, private val configure: (WebView) -> Unit = {}) : SniffPort {
    companion object {
        fun media(url: String): Boolean = Regex("\\.(m3u8|mp4|mkv|flv|mpd|ts)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }
    override suspend fun sniff(url: String, headers: Map<String, String>, timeoutMs: Long): VideoResource =
        withTimeout(timeoutMs.coerceIn(1000, 120000)) { withContext(Dispatchers.Main.immediate) {
            require(url.startsWith("http://") || url.startsWith("https://"))
            val view = WebView(context)
            try {
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                configure(view)
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean()
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val target = request?.url?.toString().orEmpty()
                            if (media(target) && completed.compareAndSet(false, true) && continuation.isActive) {
                                val combined = headers.toMutableMap()
                                request?.requestHeaders?.forEach { (k, v) -> if (!k.equals("Range", true)) combined[k] = v }
                                CookieManager.getInstance().getCookie(target)?.takeIf { it.isNotBlank() }?.let { combined["Cookie"] = it }
                                continuation.resume(VideoResource(target, "网页嗅探", combined, preferred = true))
                            }
                            return null
                        }
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { handler?.cancel() }
                    }
                    view.loadUrl(url, headers)
                }
            } finally { view.stopLoading(); view.webViewClient = WebViewClient(); view.destroy() }
        } }
}
