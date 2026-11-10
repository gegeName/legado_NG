package io.legado.features.tvbox.sniff

import android.content.Context
import android.webkit.*
import io.legado.features.api.source.VideoResource
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayInputStream
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/** Owns a fresh WebView, destroyed on success, error, timeout and cancellation. */
class WebViewSniffer(private val context: Context, private val configure: (WebView) -> Unit = {},
    private val html: String? = null) : SniffPort {
    companion object {
        fun media(url: String): Boolean = WebVideoUrls.media(url)
    }
    override suspend fun sniff(url: String, headers: Map<String, String>, timeoutMs: Long): VideoResource =
        withTimeout(timeoutMs.coerceIn(1000, 120000).milliseconds) { withContext(Dispatchers.Main.immediate) {
            require(url.startsWith("http://") || url.startsWith("https://"))
            val view = WebView(context)
            val handler = Handler(Looper.getMainLooper())
            var poll: Runnable? = null
            try {
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.mediaPlaybackRequiresUserGesture = false
                view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let {
                    view.settings.userAgentString = it.value
                }
                configure(view)
                val cookies = CookieManager.getInstance()
                cookies.setAcceptThirdPartyCookies(view, true)
                headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
                    ?.split(';')?.filter { it.isNotBlank() }?.forEach { cookies.setCookie(url, it.trim()) }
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean()
                    val page = AtomicReference(url)
                    val userAgent = view.settings.userAgentString
                    fun capture(target: String, requestHeaders: Map<String, String> = emptyMap(), explicit: Boolean = false): Boolean {
                        if (!(media(target) || explicit && WebVideoUrls.network(target))) return false
                        val defaults = mapOf("Referer" to page.get(), "User-Agent" to userAgent)
                        val cookie = cookies.getCookie(target)?.takeIf { it.isNotBlank() }
                            ?.let { mapOf("Cookie" to it) }.orEmpty()
                        // Cookies for a parser host must not be replayed to an unrelated media host.
                        val seed = WebVideoUrls.forTarget(url, target, headers)
                        val combined = WebVideoUrls.headers(defaults, seed, requestHeaders, cookie)
                        if (completed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(VideoResource(target, "网页嗅探", combined, preferred = true))
                        }
                        return true
                    }
                    continuation.invokeOnCancellation { completed.set(true) }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val target = request?.url?.toString().orEmpty()
                            if (capture(target, request?.requestHeaders.orEmpty())) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return true
                            return capture(target, request.requestHeaders) || !WebVideoUrls.network(target)
                        }
                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                            url == null || capture(url) || !WebVideoUrls.network(url)
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            url?.takeIf(WebVideoUrls::network)?.let(page::set)
                        }
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { handler?.cancel() }
                    }
                    // Recheck delayed players and frames; muted play triggers lazy media requests.
                    poll = object : Runnable {
                        override fun run() {
                            if (completed.get() || !continuation.isActive) return
                            view.evaluateJavascript("""
                                (function scan(w){var urls=[];try{
                                  w.document.querySelectorAll('video').forEach(function(v){
                                    if(v.currentSrc||v.src)urls.push(v.currentSrc||v.src);
                                    v.querySelectorAll('source').forEach(function(s){if(s.src)urls.push(s.src)});
                                    v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});
                                  });
                                  for(var i=0;i<w.frames.length;i++)urls=urls.concat(scan(w.frames[i]));
                                }catch(e){}return urls})(window)
                            """.trimIndent()) { result ->
                                if (!completed.get()) runCatching {
                                    val urls = JSONArray(result)
                                    for (index in 0 until urls.length()) {
                                        if (capture(urls.optString(index), explicit = true)) break
                                    }
                                }
                            }
                            handler.postDelayed(this, 750)
                        }
                    }
                    if (html == null) view.loadUrl(url, headers)
                    else view.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                    handler.postDelayed(checkNotNull(poll), 250)
                }
            } finally {
                poll?.let(handler::removeCallbacks)
                view.stopLoading(); view.webViewClient = WebViewClient(); view.destroy()
            }
        } }
}
