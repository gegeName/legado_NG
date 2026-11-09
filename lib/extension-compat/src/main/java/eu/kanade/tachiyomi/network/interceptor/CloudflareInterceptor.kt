

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
import java.util.concurrent.CountDownLatch

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

    override fun solve(request: Request, oldClearance: String?) {
        resolveWithWebView(request, oldClearance)
        syncCookiesToHostStore(request.url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldClearance: String?) {

        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()

        val challengeUrl = if (originalRequest.method == "GET") {
            origRequestUrl
        } else {
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
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
                        cloudflareBypassed = true
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

        latch.awaitChallengeWindow()

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.let { ExtensionServices.require().isWebViewOutdated(it) } == true
            }

            webview?.run {
                stopLoading()
                destroy()
            }
        }

        if (!cloudflareBypassed) {
            if (isWebViewOutdated) {

                CompatLog.put("系统 WebView 版本过低, 可能无法通过 Cloudflare 挑战")
            } else {
                CompatLog.put("挑战求解放弃: ${CHALLENGE_TIMEOUT_MS / 1000} 秒求解窗耗尽\n$challengeUrl")
            }

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
