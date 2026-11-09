package io.legado.features.tvbox.provider

import android.content.Context
import com.google.gson.*
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.source.*
import io.legado.features.tvbox.*
import io.legado.features.tvbox.cms.CmsSpider
import io.legado.features.tvbox.config.*
import io.legado.features.tvbox.jar.JarSpiders
import io.legado.features.tvbox.js.RhinoSpider
import io.legado.features.tvbox.js.QuickJsSpider
import io.legado.features.tvbox.js.TvBoxAssets
import io.legado.features.tvbox.proxy.LocalProxy
import io.legado.features.tvbox.sniff.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class TvBoxProvider(private val context: Context, private val settings: ConfigPort,
    private val http: TvBoxHttp, sniff: SniffPort) : SourceProvider, AutoCloseable {
    override val provider = "tvbox"
    private val mutex = Mutex()
    private val configs = linkedMapOf<String, TvBoxConfig>()
    private val spiders = java.util.concurrent.ConcurrentHashMap<SourceKey, SpiderPort>()
    private val endpoints = java.util.concurrent.ConcurrentHashMap<SourceKey, String>()
    private val files = File(context.filesDir, "feature_tvbox")
    private val jars = JarSpiders(context, File(files, "jars"), http)
    private val resolver = VideoResolver(http, sniff)
    private var proxy: LocalProxy? = null
    private var closed = false
    var loadProblem: String? = null
        private set
    init {
        settings.get("tvbox", "configs-v1")?.let { saved ->
            try { TvBoxConfigs.restore(saved).forEach { configs[it.namespace] = it } }
            catch (e: Exception) { loadProblem = e.message ?: "无效 TVBox 配置" }
        }
        com.github.catvod.net.OkHttp.transport = http
    }
    suspend fun import(text: String, base: String): List<SourceDescriptor> = mutex.withLock {
        check(!closed)
        val parsed = TvBoxConfigs.parse(text, base)
        val saved = TvBoxConfigs.encode(configs.values.filter { it.namespace != parsed.namespace } + parsed)
        invalidate(parsed.namespace)
        configs[parsed.namespace] = parsed
        save(saved)
        descriptors()
    }
    suspend fun refresh(namespace: String): List<SourceDescriptor> {
        val config = mutex.withLock { checkNotNull(configs[namespace]) }
        require(config.baseUrl.startsWith("http")) { "本地配置请重新选择文件导入" }
        val text = http.bound { http.text(config.baseUrl) }
        return mutex.withLock {
            if (closed || configs[namespace] !== config) throw CancellationException("TVBox 配置会话已变化")
            val updated = TvBoxConfigs.parse(text, config.baseUrl, namespace)
            val saved = TvBoxConfigs.encode(configs.values.filter { it.namespace != namespace } + updated)
            invalidate(namespace); configs[namespace] = updated; save(saved); descriptors()
        }
    }
    suspend fun remove(namespace: String) = mutex.withLock { invalidate(namespace); configs.remove(namespace); save() }
    private fun invalidate(namespace: String) {
        jars.invalidate(namespace)
        proxy?.invalidate(namespace)
        spiders.keys.filter { it.namespace == namespace }.forEach { key -> spiders.remove(key)?.close(); endpoints.remove(key) }
    }
    private fun save(saved: String = TvBoxConfigs.encode(configs.values)) {
        if (loadProblem != null) {
            settings.put("tvbox", "rejected-configs-v1", settings.get("tvbox", "configs-v1"))
            loadProblem = null
        }
        settings.put("tvbox", "configs-v1", saved)
    }
    private fun enabled(key: SourceKey) = settings.get("tvbox-disabled", "${key.namespace}:${key.nativeId}") != "true"
    suspend fun enable(key: SourceKey, enabled: Boolean) = mutex.withLock {
        require(key.provider == provider)
        settings.put("tvbox-disabled", "${key.namespace}:${key.nativeId}", if (enabled) null else "true")
        if (!enabled) invalidate(key.namespace)
    }
    suspend fun configurations(): List<Pair<String, String>> = mutex.withLock { configs.values.map { it.namespace to it.baseUrl } }
    private fun descriptors() = configs.values.flatMap { config -> config.sites.map { site ->
        val key = SourceKey(provider, ContentKind.VIDEO, site.key, config.namespace)
        SourceDescriptor(key, site.name, enabled = enabled(key))
    } }
    override suspend fun sources(): List<SourceDescriptor> = mutex.withLock { descriptors() }
    private fun locate(key: SourceKey): Pair<TvBoxConfig, TvBoxSite> {
        check(!closed)
        require(key.provider == provider && key.contentKind == ContentKind.VIDEO)
        val config = if (key.namespace.isNotBlank()) configs[key.namespace] else configs.values.filter { c -> c.sites.any { it.key == key.nativeId } }.singleOrNull()
        checkNotNull(config) { "TVBox 配置缺失或旧站点身份不唯一，请重新关联配置" }
        val site = config.sites.firstOrNull { it.key == key.nativeId } ?: error("TVBox 站点已移除")
        check(enabled(SourceKey(provider, ContentKind.VIDEO, site.key, config.namespace))) { "TVBox 站点已停用" }
        return config to site
    }
    private suspend fun <T> call(key: SourceKey, block: (TvBoxConfig, TvBoxSite, SpiderPort) -> T): T = mutex.withLock {
        val (config, site) = locate(key)
        val canonical = SourceKey(provider, ContentKind.VIDEO, site.key, config.namespace)
        http.bound {
            val port = spiders.getOrPut(canonical) {
                val server = proxy ?: LocalProxy(files).also { proxy = it }
                endpoints[canonical] = server.register(config.namespace, site.key) { params ->
                    val owner = synchronized(spiders) { spiders[canonical] } ?: return@register null
                    com.github.catvod.utils.Path.bound(config.namespace) {
                        com.github.catvod.Proxy.bound(checkNotNull(endpoints[canonical])) { synchronized(owner) { owner.proxy(params) } }
                    }
                }
                val spider = com.github.catvod.Proxy.bound(checkNotNull(endpoints[canonical])) { when {
                    site.isJar -> com.github.catvod.utils.Path.bound(config.namespace) { jars.open(config, site) }
                    site.isJs -> {
                        val asset: (String) -> String = TvBoxAssets(context, http)::read
                        if (android.os.Build.VERSION.SDK_INT >= 23) QuickJsSpider(context, site, config.namespace, http, settings, asset, { checkNotNull(endpoints[canonical]) })
                        else RhinoSpider(site, config.namespace, http, settings, asset, { checkNotNull(endpoints[canonical]) })
                    }
                    site.type in listOf(0, 1) && site.api.startsWith("http") -> CmsSpider(site, http)
                    else -> throw UnsupportedOperationException("TVBox 后端未支持: type=${site.type} ${site.api}")
                } }
                spider
            }
            com.github.catvod.utils.Path.bound(config.namespace) {
                com.github.catvod.Proxy.bound(checkNotNull(endpoints[canonical])) { synchronized(port) { block(config, site, port) } }
            }
        }
    }
    override suspend fun filters(source: SourceKey): FilterSession = call(source) { _, _, spider ->
        val home = TvBoxJson.root(spider.home(true))
        TvBoxFilters(source, home)
    }
    override suspend fun searchFilters(source: SourceKey): FilterSession = TvBoxFilters(source, JsonObject())
    override suspend fun search(source: SourceKey, query: String, page: Int, filters: FilterSession): CatalogPage = call(source) { _, site, spider ->
        require(filters.source == source); check(site.searchable) { "站点不支持搜索" }; TvBoxJson.page(spider.search(query, page.coerceAtLeast(1)), page)
    }
    override suspend fun discover(source: SourceKey, section: CatalogSection, page: Int, filters: FilterSession): CatalogPage = call(source) { _, _, spider ->
        val session = filters as? TvBoxFilters ?: error("TVBox 筛选会话不匹配")
        require(session.source == source)
        TvBoxJson.page(spider.category(session.category(), page.coerceAtLeast(1), session.extend()), page)
    }
    override suspend fun details(source: SourceKey, item: CatalogItem): CatalogItem = call(source) { _, _, spider ->
        TvBoxJson.items(spider.detail(item.id)).firstOrNull { it.id == item.id } ?: error("TVBox 详情为空")
    }
    override suspend fun chapters(source: SourceKey, item: CatalogItem): List<ChapterDescriptor> = TvBoxJson.chapters(details(source, item))
    override suspend fun content(source: SourceKey, item: CatalogItem, chapter: ChapterDescriptor): ContentResult {
        val episode = TvBoxJson.episode(chapter)
        val parsed = call(source) { config, site, spider -> Triple(config, site, spider.play(episode.line, episode.url, config.flags)) }
        val resource = resolver.resolve(parsed.first, parsed.second, parsed.third, episode.line) { parser, all, flag, url ->
            call(source) { config, _, spider ->
                check(config === parsed.first) { "TVBox 配置已更新，请重新解析" }
                spider.parse(parser, all, flag, url)
            }
        }
        val canonical = if (source.namespace.isBlank()) mutex.withLock { locate(source).first.let { source.copy(namespace = it.namespace) } } else source
        val media = mutex.withLock {
            check(configs[canonical.namespace] === parsed.first) { "TVBox 配置已更新，请重新解析" }
            val endpoint = checkNotNull(endpoints[canonical]) { "TVBox 播放会话已关闭" }
            resource.copy(url = checkNotNull(proxy).logical(resource.url, endpoint))
        }
        return ContentResult.Videos(listOf(media))
    }
    override fun close() { closed = true; proxy?.close(); proxy = null; spiders.values.forEach { runCatching { it.close() } }; spiders.clear(); jars.close() }
}
