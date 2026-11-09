package io.legado.features.adapter

import android.content.Context
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.source.SourceKey
import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.provider.TvBoxProvider
import io.legado.features.tvbox.sniff.WebViewSniffer
import okhttp3.OkHttpClient

object TvBoxFeatures {
    fun isWorkerProcess(): Boolean = runCatching {
        java.io.File("/proc/self/cmdline").inputStream().use { it.readBytes().toString(Charsets.UTF_8).substringBefore('\u0000').endsWith(":feature_tvbox_js") }
    }.getOrDefault(false)
    @Volatile private var runtime: TvBoxProvider? = null
    @Synchronized fun start(context: Context, config: ConfigPort, client: OkHttpClient) {
        if (runtime == null) {
            transport = TvBoxHttp(client)
            runtime = TvBoxProvider(context.applicationContext, config, checkNotNull(transport), WebViewSniffer(context.applicationContext))
        }
    }
    private fun engine() = checkNotNull(runtime) { "TVBox 模块尚未初始化" }
    fun provider(): io.legado.features.api.source.SourceProvider = engine()
    fun loadProblem(): String? = engine().loadProblem
    suspend fun import(text: String, base: String) = engine().import(text, base)
    suspend fun importUrl(url: String): List<io.legado.features.api.source.SourceDescriptor> {
        val http = transport ?: error("TVBox 网络尚未初始化")
        return import(http.bound { http.text(url) }, url)
    }
    @Volatile private var transport: TvBoxHttp? = null
    suspend fun configurations() = engine().configurations()
    suspend fun refresh(namespace: String) = engine().refresh(namespace)
    suspend fun remove(namespace: String) = engine().remove(namespace)
    suspend fun enable(key: SourceKey, enabled: Boolean) = engine().enable(key, enabled)
    @Synchronized fun close() { runtime?.close(); runtime = null; transport = null }
}
