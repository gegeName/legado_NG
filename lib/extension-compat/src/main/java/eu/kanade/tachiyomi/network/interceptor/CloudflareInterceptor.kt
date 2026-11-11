

package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import io.legado.features.extensioncompat.CompatLog
import io.legado.features.extensioncompat.ExtensionServices
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException

class CloudflareInterceptor(
    private val context: Context,
    private val cookieResolver: ChallengeCookieResolver,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun canCarrySolvedCookies(request: Request): Boolean =
        cookieResolver is CookieJar || super.canCarrySolvedCookies(request)

    override fun oldClearance(request: Request): String? =
        cookieResolver.get(request.url)
            .firstOrNull { it.name == COOKIE_CLEARANCE }
            ?.value

    override fun clearClearance(request: Request) {
        cookieResolver.remove(request.url, COOKIE_NAMES, 0)
    }

    override fun onChallengeResponse(response: Response, retried: Boolean) {
        // BridgeInterceptor replaces response.request with the pre-bridge request.
        val request = ExtensionCookieInterceptor.transportRequest(response.request) ?: response.request
        val stored = oldClearance(request)
        val sent = request.headers("Cookie").firstNotNullOfOrNull { clearanceValue(it) }
        CompatLog.put("扩展验证 ${if (retried) "重试" else "挑战"}: ${request.url.host} HTTP ${response.code}; " +
            "已存凭据=${!stored.isNullOrEmpty()}, 请求携带凭据=${!sent.isNullOrEmpty()}, 凭据一致=${stored != null && stored == sent}; " +
            "协议=${response.protocol}")
    }

    override fun solve(request: Request, oldClearance: String?) {
        solve(request, oldClearance) { false }
    }

    override fun solve(request: Request, oldClearance: String?, isCancelled: () -> Boolean) {
        try {
            resolveWithWebView(request, oldClearance, isCancelled)
        } catch (e: CloudflareBypassException) {
            if (isCancelled()) throw IOException("Canceled")
            // Keep the request UA; let the shared WebView CookieManager supply current cookies.
            val headers = parseHeaders(request.headers).filterKeys { !it.equals("Cookie", true) }
            if (!ExtensionServices.require().verifyChallenge(challengeUrl(request), headers, oldClearance, isCancelled)) {
                throw e
            }
        }
        if (isCancelled()) throw IOException("Canceled")
        syncCookiesToHostStore(request.url)
    }

    private fun challengeUrl(request: Request): String = if (request.method == "GET") {
        request.url.toString()
    } else {
        request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldClearance: String?, isCancelled: () -> Boolean) {

        val latch = CountDownLatch(1)

        var webview: WebView? = null

        val cloudflareBypassed = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)

        val origRequestUrl = originalRequest.url.toString()

        val challengeUrl = challengeUrl(originalRequest)
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            if (abandoned.get()) return@execute
            webview = createWebView(originalRequest)

            webview.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun interactiveDetected() {

                        latch.countDown()
                    }
                },
                "mihon",
            )

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieResolver.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == COOKIE_CLEARANCE }
                            .let { it != null && it.value != oldClearance }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed.set(true)
                        latch.countDown()
                        return
                    }

                    view.evaluateJavascript(
                        """
                            addEventListener("message", ({data}) => {
                                if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                                    mihon.interactiveDetected();
                                }
                            })
                        """.trimIndent(),
                        null,
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {

                    if (request?.isForMainFrame == true &&
                        errorResponse?.responseHeaders?.get("cf-mitigated") != "challenge"
                    ) {
                        CompatLog.put(
                            "挑战求解放弃: 主文档返回非挑战错误 ${errorResponse?.statusCode}\n$challengeUrl"
                        )
                        latch.countDown()
                    }
                }
            }

            webview.loadUrl(challengeUrl, headers)
        }

        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHALLENGE_TIMEOUT_MS)
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) throw IOException("Canceled")
                if (System.nanoTime() >= deadline) break
            }
        } finally {
            abandoned.set(true)
            executor.execute {
                webview?.run {
                    stopLoading()
                    destroy()
                }
            }
        }

        if (!cloudflareBypassed.get()) {
            CompatLog.put("自动验证未完成，尝试打开宿主验证页面\n$challengeUrl")
            throw CloudflareBypassException()
        }
    }

    private fun syncCookiesToHostStore(url: HttpUrl) {
        try {
            val cookieStr = CookieManager.getInstance().getCookie(url.toString()) ?: return
            ExtensionServices.require().syncCookies(url.toString(), cookieStr)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {

        }
    }
}

private val COOKIE_NAMES = listOf("cf_clearance")
