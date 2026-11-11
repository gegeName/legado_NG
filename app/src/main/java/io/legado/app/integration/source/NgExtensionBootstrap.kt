package io.legado.app.integration.source

import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.adapter.TvBoxFeatures
import io.legado.features.adapter.SourceFeatures
import io.legado.features.adapter.ExtensionFeatureHost
import io.legado.features.api.host.ConfigPort
import kotlinx.coroutines.*

/** The process owns one runtime. Startup only reads local state; repositories refresh explicitly. */
object NgExtensionBootstrap {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Synchronized fun start(application: Application) {
        if (started) return
        val prefs = application.getSharedPreferences("feature_modules", Context.MODE_PRIVATE)
        // Challenge cookies are tied to the browser identity. NG's desktop Cronet UA can
        // advertise an unrelated Chrome version; extensions verify in Android WebView.
        val extensionUserAgent = runCatching {
            WebSettings.getDefaultUserAgent(application)
                .replace("; wv", "")
                .replace(" Version/4.0", "")
        }.getOrElse { AppConfig.userAgent }
        val featureConfig = object : ConfigPort {
            override fun get(namespace: String, key: String) = prefs.getString("$namespace:$key", null)
            override fun put(namespace: String, key: String, value: String?) { prefs.edit().putString("$namespace:$key", value).apply() }
        }
        TvBoxFeatures.start(application, featureConfig, okHttpClient)
        SourceFeatures.configure(featureConfig)
        scope.launch {
            try {
                TvBoxFeatures.loadProblem()?.let { AppLog.put("TVBox 本地配置读取失败：$it") }
                NgExtensionSourceBridge.syncSources(TvBoxFeatures.provider().sources(), setOf("tvbox"))
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.put("同步 TVBox 来源失败", e) }
        }
        ExtensionFeatures.start(object : ExtensionFeatureHost {
            override val application = application
            override val client = okHttpClient
            override val config = object : ConfigPort {
                override fun get(namespace: String, key: String): String? = prefs.getString("$namespace:$key", null)
                override fun put(namespace: String, key: String, value: String?) { prefs.edit().putString("$namespace:$key", value).apply() }
            }
            override fun userAgent() = extensionUserAgent
            override fun log(message: String, error: Throwable?) { AppLog.put(message, error) }
            override fun supportsWebView(context: Context) = WebViewCompat.getCurrentWebViewPackage(context) != null
            override fun configureWebView(view: WebView, userAgent: String) {
                view.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = userAgent }
            }
            override fun isWebViewOutdated(view: WebView) = false
            override fun syncCookies(url: String, cookie: String) { CookieStore.replaceCookie(url, cookie) }
            override fun verifyChallenge(url: String, headers: Map<String, String>, oldClearance: String?, isCancelled: () -> Boolean) =
                NgExtensionVerification.verify(application, url, headers, oldClearance, isCancelled)
        })
        started = true
        scope.launch {
            ExtensionFeatures.sourceChanges().collect { sources ->
                try { NgExtensionSourceBridge.syncSources(sources, setOf("mihon", "aniyomi")) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { AppLog.put("同步扩展来源失败", e) }
            }
        }
    }
    fun close() { scope.cancel(); TvBoxFeatures.close(); ExtensionFeatures.close() }
}
